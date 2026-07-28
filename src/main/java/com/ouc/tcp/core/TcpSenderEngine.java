package com.ouc.tcp.core;

import com.ouc.tcp.buffer.PendingDataBuffer;
import com.ouc.tcp.buffer.RetransmissionQueue;
import com.ouc.tcp.checksum.TcpChecksum;
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
            queueResult.rttSample().ifPresent(rttEstimator::recordSample);
            if (retransmissionQueue.segmentCount() == 0) {
                retransmissionTimer.stop();
            } else {
                restartRetransmissionTimer();
            }
        }

        return ackResult(
                disposition,
                newlyAcknowledgedBytes,
                windowChanged,
                emitPermittedSegments());
    }

    public synchronized List<TcpSegment> updateCongestionWindow(long congestionWindow) {
        controlBlock.setCongestionWindow(congestionWindow);
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
        List<TcpSegment> transmissions = new ArrayList<>();
        while (!pendingData.isEmpty() && controlBlock.usableWindow() > 0) {
            int payloadLength = (int) Math.min(
                    Math.min(
                            config.senderMaximumSegmentSize(),
                            controlBlock.usableWindow()),
                    pendingData.size());
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
            if (startTimer) {
                restartRetransmissionTimer();
            }
        }
        return List.copyOf(transmissions);
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
            retransmission =
                    retransmissionQueue.retransmitEarliest(clock.nanoTime());
            rttEstimator.backOff();
            restartRetransmissionTimer();
        }
        retransmissionSink.accept(retransmission);
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
