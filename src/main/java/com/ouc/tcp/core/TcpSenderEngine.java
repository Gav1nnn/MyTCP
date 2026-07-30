package com.ouc.tcp.core;

import com.ouc.tcp.buffer.PendingDataBuffer;
import com.ouc.tcp.buffer.RetransmissionQueue;
import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.congestion.CongestionPhase;
import com.ouc.tcp.congestion.DuplicateAckAction;
import com.ouc.tcp.congestion.RenoCongestionController;
import com.ouc.tcp.timer.Clock;
import com.ouc.tcp.timer.RetransmissionTimer;
import com.ouc.tcp.timer.RttEstimator;
import com.ouc.tcp.timer.Scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Established-state TCP send path with cumulative acknowledgments and flow control.
 */
public final class TcpSenderEngine {
    private final SenderConfig config;
    private final SendControlBlock controlBlock;
    private final PendingDataBuffer pendingData = new PendingDataBuffer();
    private final RetransmissionQueue retransmissionQueue = new RetransmissionQueue();
    private final Clock clock;
    private final Consumer<TcpSegment> retransmissionSink;
    private final RttEstimator rttEstimator;
    private final RetransmissionTimer retransmissionTimer;
    private final RetransmissionTimer persistTimer;
    private final RenoCongestionController congestionController;
    private final long initialCongestionWindow;

    private Duration persistInterval;
    private Long lastDataSentNanos;
    private SequenceNumber32 acknowledgmentNumber;
    private int localAdvertisedWindow;

    public TcpSenderEngine(
            SenderConfig config,
            Clock clock,
            Scheduler scheduler,
            Consumer<TcpSegment> retransmissionSink) {
        this(
                config,
                clock,
                scheduler,
                retransmissionSink,
                new RttEstimator());
    }

    public TcpSenderEngine(
            SenderConfig config,
            Clock clock,
            Scheduler scheduler,
            Consumer<TcpSegment> retransmissionSink,
            RttEstimator rttEstimator) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retransmissionSink =
                Objects.requireNonNull(retransmissionSink, "retransmissionSink");
        this.rttEstimator =
                Objects.requireNonNull(rttEstimator, "rttEstimator");
        this.retransmissionTimer =
                new RetransmissionTimer(Objects.requireNonNull(scheduler, "scheduler"));
        this.persistTimer = new RetransmissionTimer(scheduler);
        this.initialCongestionWindow = config.initialCongestionWindow();
        this.congestionController = new RenoCongestionController(
                config.senderMaximumSegmentSize(),
                config.initialCongestionWindow(),
                config.initialSlowStartThreshold());
        this.controlBlock = new SendControlBlock(
                config.initialSendNext(),
                config.peerAdvertisedWindow(),
                config.initialCongestionWindow());
        acknowledgmentNumber = config.acknowledgmentNumber();
        localAdvertisedWindow = config.localAdvertisedWindow();
    }

    /**
     * Queues application bytes and returns the segments currently permitted by
     * both the congestion and receiver windows.
     */
    public synchronized List<TcpSegment> queueData(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length > 0) {
            applyIdleRestartIfNeeded();
        }
        pendingData.append(data);
        List<TcpSegment> transmissions = emitPermittedSegments();
        updatePersistTimer();
        return transmissions;
    }

    public synchronized AckProcessingResult receiveAcknowledgment(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        if (!matchesConnection(segment)) {
            return ackResult(AckDisposition.WRONG_CONNECTION, 0, false, List.of());
        }
        if (!hasValidChecksum(segment)) {
            return ackResult(AckDisposition.CHECKSUM_FAILED, 0, false, List.of());
        }
        if (!segment.hasFlag(TcpFlag.ACK)) {
            return ackResult(AckDisposition.NOT_AN_ACK, 0, false, List.of());
        }

        SequenceNumber32 acknowledgment =
                SequenceNumber32.of(segment.acknowledgmentNumber());
        AckDisposition disposition = classifyAcknowledgment(acknowledgment);
        if (disposition == AckDisposition.FUTURE_ACK
                || disposition == AckDisposition.UNACCEPTABLE_ACK
                || disposition == AckDisposition.OLD_ACK) {
            return ackResult(disposition, 0, false, List.of());
        }

        int previousWindow = controlBlock.sendWindow();
        controlBlock.updateSendWindow(
                SequenceNumber32.of(segment.sequenceNumber()),
                acknowledgment,
                segment.advertisedWindow());
        boolean windowChanged = previousWindow != controlBlock.sendWindow();
        boolean windowReopened =
                previousWindow == 0 && controlBlock.sendWindow() > 0;
        List<TcpSegment> transmissions = new ArrayList<>();
        boolean congestionDuplicate =
                disposition == AckDisposition.DUPLICATE_ACK
                        && isCongestionDuplicate(segment, previousWindow);
        DuplicateAckAction duplicateAckAction = DuplicateAckAction.NONE;

        long newlyAcknowledgedBytes = 0;
        if (disposition == AckDisposition.NEW_ACK) {
            RetransmissionQueue.AcknowledgmentResult queueResult =
                    retransmissionQueue.acknowledge(
                            acknowledgment, clock.nanoTime());
            newlyAcknowledgedBytes = queueResult.acknowledgedBytes();
            long controlBlockAdvance =
                    controlBlock.sendUnacknowledged().distanceTo(acknowledgment);
            if (newlyAcknowledgedBytes != controlBlockAdvance) {
                throw new IllegalStateException(
                        "retransmission queue and send sequence space diverged");
            }
            controlBlock.advanceSendUnacknowledged(acknowledgment);
            congestionController.onNewAcknowledgment(newlyAcknowledgedBytes);
            synchronizeCongestionWindow();
            queueResult.rttSample().ifPresent(rttEstimator::recordSample);
            if (retransmissionQueue.segmentCount() == 0) {
                retransmissionTimer.stop();
            } else {
                restartRetransmissionTimer();
            }
        } else if (congestionDuplicate) {
            duplicateAckAction = congestionController.onDuplicateAcknowledgment(
                    controlBlock.flightSize());
            synchronizeCongestionWindow();
            if (duplicateAckAction == DuplicateAckAction.FAST_RETRANSMIT) {
                transmissions.add(refreshReceiveFields(
                        retransmissionQueue.retransmitEarliestFast(
                                clock.nanoTime())));
                lastDataSentNanos = clock.nanoTime();
            }
        } else {
            congestionController.onNonDuplicateAcknowledgment();
        }

        if (windowReopened && retransmissionQueue.segmentCount() > 0) {
            transmissions.add(refreshReceiveFields(
                    retransmissionQueue.retransmitEarliestFast(clock.nanoTime())));
            lastDataSentNanos = clock.nanoTime();
        }
        if (duplicateAckAction == DuplicateAckAction.LIMITED_TRANSMIT) {
            List<TcpSegment> limitedTransmission = emitPermittedSegments(
                    config.senderMaximumSegmentSize(),
                    2L * config.senderMaximumSegmentSize());
            congestionController.recordLimitedTransmit(
                    payloadBytes(limitedTransmission));
            transmissions.addAll(limitedTransmission);
        } else {
            transmissions.addAll(congestionDuplicate
                    ? emitPermittedSegments(config.senderMaximumSegmentSize())
                    : emitPermittedSegments());
        }
        updatePersistTimer();
        return ackResult(
                disposition,
                newlyAcknowledgedBytes,
                windowChanged,
                transmissions);
    }

    public synchronized List<TcpSegment> updateCongestionWindow(long congestionWindow) {
        congestionController.setCongestionWindow(congestionWindow);
        synchronizeCongestionWindow();
        return emitPermittedSegments();
    }

    public synchronized void updateReceiveState(
            SequenceNumber32 currentReceiveNext,
            int currentAdvertisedWindow) {
        acknowledgmentNumber =
                Objects.requireNonNull(currentReceiveNext, "currentReceiveNext");
        if (currentAdvertisedWindow < 0
                || currentAdvertisedWindow > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException(
                    "currentAdvertisedWindow must be an unsigned 16-bit value");
        }
        localAdvertisedWindow = currentAdvertisedWindow;
    }

    public synchronized SequenceNumber32 sendUnacknowledged() {
        return controlBlock.sendUnacknowledged();
    }

    public synchronized SequenceNumber32 sendNext() {
        return controlBlock.sendNext();
    }

    public synchronized int sendWindow() {
        return controlBlock.sendWindow();
    }

    public synchronized long congestionWindow() {
        return controlBlock.congestionWindow();
    }

    public synchronized long slowStartThreshold() {
        return congestionController.slowStartThreshold();
    }

    public synchronized int duplicateAckCount() {
        return congestionController.duplicateAckCount();
    }

    public synchronized CongestionPhase congestionPhase() {
        return congestionController.phase();
    }

    public synchronized long flightSize() {
        return controlBlock.flightSize();
    }

    public synchronized int pendingByteCount() {
        return pendingData.size();
    }

    public synchronized List<TcpSegment> outstandingSegments() {
        return retransmissionQueue.segments();
    }

    public synchronized Duration retransmissionTimeout() {
        return rttEstimator.retransmissionTimeout();
    }

    public synchronized Optional<Duration> smoothedRtt() {
        return rttEstimator.smoothedRtt();
    }

    public synchronized Optional<Duration> rttVariation() {
        return rttEstimator.rttVariation();
    }

    public synchronized boolean retransmissionTimerRunning() {
        return retransmissionTimer.isRunning();
    }

    public synchronized boolean persistTimerRunning() {
        return persistTimer.isRunning();
    }

    public synchronized Optional<Duration> persistInterval() {
        return Optional.ofNullable(persistInterval);
    }

    private List<TcpSegment> emitPermittedSegments() {
        return emitPermittedSegments(Long.MAX_VALUE, 0);
    }

    private List<TcpSegment> emitPermittedSegments(long byteLimit) {
        return emitPermittedSegments(byteLimit, 0);
    }

    private List<TcpSegment> emitPermittedSegments(
            long byteLimit, long additionalCongestionWindow) {
        List<TcpSegment> transmissions = new ArrayList<>();
        long emittedBytes = 0;
        while (!pendingData.isEmpty()
                && usableWindow(additionalCongestionWindow) > 0
                && emittedBytes < byteLimit) {
            int payloadLength = (int) Math.min(
                    Math.min(
                            config.senderMaximumSegmentSize(),
                            usableWindow(additionalCongestionWindow)),
                    Math.min(pendingData.size(), byteLimit - emittedBytes));
            byte[] payload = pendingData.take(payloadLength);
            TcpSegment segment =
                    createDataSegment(controlBlock.sendNext(), payload);
            boolean startTimer = retransmissionQueue.segmentCount() == 0;
            retransmissionQueue.add(segment, clock.nanoTime());
            controlBlock.advanceSendNext(payloadLength);
            transmissions.add(segment);
            emittedBytes += payloadLength;
            lastDataSentNanos = clock.nanoTime();
            if (startTimer) {
                restartRetransmissionTimer();
            }
        }
        return List.copyOf(transmissions);
    }

    private long usableWindow(long additionalCongestionWindow) {
        long extendedCongestionWindow = controlBlock.congestionWindow()
                >= SequenceNumber32.HALF_RANGE - 1 - additionalCongestionWindow
                        ? SequenceNumber32.HALF_RANGE - 1
                        : controlBlock.congestionWindow()
                                + additionalCongestionWindow;
        long effectiveWindow =
                Math.min(extendedCongestionWindow, controlBlock.sendWindow());
        return Math.max(0, effectiveWindow - controlBlock.flightSize());
    }

    private static long payloadBytes(List<TcpSegment> segments) {
        return segments.stream()
                .mapToLong(TcpSegment::payloadLength)
                .sum();
    }

    private void restartRetransmissionTimer() {
        retransmissionTimer.startOrRestart(
                rttEstimator.retransmissionTimeout(), this::onRetransmissionTimeout);
    }

    private void onRetransmissionTimeout() {
        TcpSegment retransmission;
        synchronized (this) {
            if (retransmissionQueue.segmentCount() == 0) {
                return;
            }
            congestionController.onRetransmissionTimeout(
                    controlBlock.flightSize(),
                    retransmissionQueue.earliestHasTimedOut());
            synchronizeCongestionWindow();
            retransmission =
                    refreshReceiveFields(
                            retransmissionQueue.retransmitEarliestDueToTimeout(
                                    clock.nanoTime()));
            lastDataSentNanos = clock.nanoTime();
            rttEstimator.backOff();
            restartRetransmissionTimer();
        }
        retransmissionSink.accept(retransmission);
    }

    private void updatePersistTimer() {
        boolean persistCondition = controlBlock.sendWindow() == 0
                && (pendingData.size() > 0
                        || retransmissionQueue.segmentCount() > 0);
        if (!persistCondition) {
            boolean wasPersisting =
                    persistTimer.isRunning() || persistInterval != null;
            persistTimer.stop();
            persistInterval = null;
            if (wasPersisting
                    && retransmissionQueue.segmentCount() > 0
                    && !retransmissionTimer.isRunning()) {
                restartRetransmissionTimer();
            }
            return;
        }
        retransmissionTimer.stop();
        if (!persistTimer.isRunning()) {
            if (persistInterval == null) {
                persistInterval = rttEstimator.retransmissionTimeout();
            }
            persistTimer.startOrRestart(
                    persistInterval, this::onPersistTimeout);
        }
    }

    private void onPersistTimeout() {
        TcpSegment probe;
        synchronized (this) {
            boolean persistCondition = controlBlock.sendWindow() == 0
                    && (pendingData.size() > 0
                            || retransmissionQueue.segmentCount() > 0);
            if (!persistCondition) {
                persistInterval = null;
                return;
            }
            if (retransmissionQueue.segmentCount() > 0) {
                probe = refreshReceiveFields(
                        retransmissionQueue.retransmitEarliestFast(clock.nanoTime()));
            } else {
                byte[] probePayload = pendingData.peek(
                        Math.min(
                                config.senderMaximumSegmentSize(),
                                pendingData.size()));
                probe = createDataSegment(controlBlock.sendNext(), probePayload);
            }
            lastDataSentNanos = clock.nanoTime();
            persistInterval = doubledPersistInterval(persistInterval);
            persistTimer.startOrRestart(
                    persistInterval, this::onPersistTimeout);
        }
        retransmissionSink.accept(probe);
    }

    private void applyIdleRestartIfNeeded() {
        if (lastDataSentNanos == null
                || retransmissionQueue.segmentCount() > 0
                || pendingData.size() > 0) {
            return;
        }
        long idleNanos = clock.nanoTime() - lastDataSentNanos;
        if (idleNanos > rttEstimator.retransmissionTimeout().toNanos()) {
            congestionController.onIdleRestart(initialCongestionWindow);
            synchronizeCongestionWindow();
        }
    }

    private Duration doubledPersistInterval(Duration interval) {
        Duration doubled;
        try {
            doubled = interval.multipliedBy(2);
        } catch (ArithmeticException overflow) {
            return RttEstimator.DEFAULT_MAXIMUM_RTO;
        }
        return doubled.compareTo(RttEstimator.DEFAULT_MAXIMUM_RTO) > 0
                ? RttEstimator.DEFAULT_MAXIMUM_RTO
                : doubled;
    }

    private void synchronizeCongestionWindow() {
        controlBlock.setCongestionWindow(
                congestionController.congestionWindow());
    }

    private boolean isCongestionDuplicate(
            TcpSegment segment, int previousWindow) {
        return controlBlock.flightSize() > 0
                && segment.payloadLength() == 0
                && !segment.hasFlag(TcpFlag.SYN)
                && !segment.hasFlag(TcpFlag.FIN)
                && segment.advertisedWindow() == previousWindow;
    }

    private TcpSegment createDataSegment(
            SequenceNumber32 sequenceNumber, byte[] payload) {
        return TcpChecksum.apply(new TcpSegment(
                config.localAddress(),
                config.remoteAddress(),
                config.localPort(),
                config.remotePort(),
                sequenceNumber.toLong(),
                acknowledgmentNumber.toLong(),
                Set.of(TcpFlag.ACK),
                localAdvertisedWindow,
                0,
                payload));
    }

    private TcpSegment refreshReceiveFields(TcpSegment segment) {
        return TcpChecksum.apply(segment
                .withAcknowledgmentNumber(acknowledgmentNumber.toLong())
                .withAdvertisedWindow(localAdvertisedWindow));
    }

    private AckDisposition classifyAcknowledgment(SequenceNumber32 acknowledgment) {
        SequenceNumber32 sendUnacknowledged = controlBlock.sendUnacknowledged();
        SequenceNumber32 sendNext = controlBlock.sendNext();

        if (acknowledgment.equals(sendUnacknowledged)) {
            return AckDisposition.DUPLICATE_ACK;
        }
        if (acknowledgment.isBefore(sendUnacknowledged)) {
            return AckDisposition.OLD_ACK;
        }
        if (sendNext.isBefore(acknowledgment)) {
            return AckDisposition.FUTURE_ACK;
        }
        if (sendUnacknowledged.isBefore(acknowledgment)
                && acknowledgment.isBeforeOrEqual(sendNext)) {
            return AckDisposition.NEW_ACK;
        }
        return AckDisposition.UNACCEPTABLE_ACK;
    }

    private AckProcessingResult ackResult(
            AckDisposition disposition,
            long newlyAcknowledgedBytes,
            boolean windowChanged,
            List<TcpSegment> transmissions) {
        return new AckProcessingResult(
                disposition,
                newlyAcknowledgedBytes,
                windowChanged,
                transmissions);
    }

    private boolean matchesConnection(TcpSegment segment) {
        return segment.sourceAddress().equals(config.remoteAddress())
                && segment.destinationAddress().equals(config.localAddress())
                && segment.sourcePort() == config.remotePort()
                && segment.destinationPort() == config.localPort();
    }

    private static boolean hasValidChecksum(TcpSegment segment) {
        try {
            return TcpChecksum.isValid(segment);
        } catch (IllegalArgumentException malformedSegment) {
            return false;
        }
    }
}
