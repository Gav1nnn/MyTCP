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
    private final RenoCongestionController congestionController;

    public TcpSenderEngine(
            SenderConfig config,
            Clock clock,
            Scheduler scheduler,
            Consumer<TcpSegment> retransmissionSink) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retransmissionSink =
                Objects.requireNonNull(retransmissionSink, "retransmissionSink");
        this.rttEstimator = new RttEstimator();
        this.retransmissionTimer =
                new RetransmissionTimer(Objects.requireNonNull(scheduler, "scheduler"));
        this.congestionController = new RenoCongestionController(
                config.senderMaximumSegmentSize(),
                config.initialCongestionWindow(),
                config.initialSlowStartThreshold());
        this.controlBlock = new SendControlBlock(
                config.initialSendNext(),
                config.peerAdvertisedWindow(),
                config.initialCongestionWindow());
    }

    /**
     * Queues application bytes and returns the segments currently permitted by
     * both the congestion and receiver windows.
     */
    public synchronized List<TcpSegment> queueData(byte[] data) {
        pendingData.append(data);
        return emitPermittedSegments();
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
                transmissions.add(
                        retransmissionQueue.retransmitEarliestFast(
                                clock.nanoTime()));
            }
        } else {
            congestionController.onNonDuplicateAcknowledgment();
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
            TcpSegment segment = TcpChecksum.apply(new TcpSegment(
                    config.localAddress(),
                    config.remoteAddress(),
                    config.localPort(),
                    config.remotePort(),
                    controlBlock.sendNext().toLong(),
                    config.acknowledgmentNumber().toLong(),
                    Set.of(TcpFlag.ACK),
                    config.localAdvertisedWindow(),
                    0,
                    payload));
            boolean startTimer = retransmissionQueue.segmentCount() == 0;
            retransmissionQueue.add(segment, clock.nanoTime());
            controlBlock.advanceSendNext(payloadLength);
            transmissions.add(segment);
            emittedBytes += payloadLength;
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
                    retransmissionQueue.retransmitEarliestDueToTimeout(
                            clock.nanoTime());
            rttEstimator.backOff();
            restartRetransmissionTimer();
        }
        retransmissionSink.accept(retransmission);
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
