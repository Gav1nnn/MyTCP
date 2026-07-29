package com.ouc.tcp.congestion;

import com.ouc.tcp.core.SequenceNumber32;

/**
 * Byte-counting implementation of RFC 5681 Reno congestion control.
 */
public final class RenoCongestionController {
    private static final long MAX_WINDOW = SequenceNumber32.HALF_RANGE - 1;

    private final int senderMaximumSegmentSize;

    private long congestionWindow;
    private long slowStartThreshold;
    private long congestionAvoidanceAckedBytes;
    private long limitedTransmitBytes;
    private int duplicateAckCount;
    private CongestionPhase phase;

    public RenoCongestionController(
            int senderMaximumSegmentSize,
            long initialCongestionWindow,
            long initialSlowStartThreshold) {
        if (senderMaximumSegmentSize < 1) {
            throw new IllegalArgumentException(
                    "senderMaximumSegmentSize must be positive");
        }
        validateWindow(initialCongestionWindow, "initialCongestionWindow");
        validateWindow(initialSlowStartThreshold, "initialSlowStartThreshold");
        this.senderMaximumSegmentSize = senderMaximumSegmentSize;
        congestionWindow = initialCongestionWindow;
        slowStartThreshold = initialSlowStartThreshold;
        phase = initialCongestionWindow < initialSlowStartThreshold
                ? CongestionPhase.SLOW_START
                : CongestionPhase.CONGESTION_AVOIDANCE;
    }

    public void onNewAcknowledgment(long newlyAcknowledgedBytes) {
        if (newlyAcknowledgedBytes <= 0) {
            throw new IllegalArgumentException(
                    "newlyAcknowledgedBytes must be positive");
        }

        duplicateAckCount = 0;
        limitedTransmitBytes = 0;
        if (phase == CongestionPhase.FAST_RECOVERY) {
            congestionWindow = slowStartThreshold;
            congestionAvoidanceAckedBytes = 0;
            phase = CongestionPhase.CONGESTION_AVOIDANCE;
            return;
        }

        if (phase == CongestionPhase.SLOW_START) {
            long increment = Math.min(
                    newlyAcknowledgedBytes, senderMaximumSegmentSize);
            congestionWindow = cappedAdd(congestionWindow, increment);
            if (congestionWindow >= slowStartThreshold) {
                phase = CongestionPhase.CONGESTION_AVOIDANCE;
                congestionAvoidanceAckedBytes = 0;
            }
            return;
        }

        congestionAvoidanceAckedBytes = cappedAdd(
                congestionAvoidanceAckedBytes, newlyAcknowledgedBytes);
        if (congestionAvoidanceAckedBytes >= congestionWindow) {
            long previousWindow = congestionWindow;
            congestionWindow = cappedAdd(
                    congestionWindow, senderMaximumSegmentSize);
            congestionAvoidanceAckedBytes -= previousWindow;
        }
    }

    public DuplicateAckAction onDuplicateAcknowledgment(long flightSize) {
        if (flightSize <= 0) {
            throw new IllegalArgumentException("flightSize must be positive");
        }
        duplicateAckCount++;

        if (phase == CongestionPhase.FAST_RECOVERY) {
            congestionWindow = cappedAdd(
                    congestionWindow, senderMaximumSegmentSize);
            return DuplicateAckAction.NONE;
        }
        if (duplicateAckCount < 3) {
            return DuplicateAckAction.LIMITED_TRANSMIT;
        }

        long flightBeforeLimitedTransmit =
                Math.max(1, flightSize - limitedTransmitBytes);
        slowStartThreshold = lossThreshold(flightBeforeLimitedTransmit);
        congestionWindow = cappedAdd(
                slowStartThreshold, 3L * senderMaximumSegmentSize);
        phase = CongestionPhase.FAST_RECOVERY;
        congestionAvoidanceAckedBytes = 0;
        limitedTransmitBytes = 0;
        return DuplicateAckAction.FAST_RETRANSMIT;
    }

    public void recordLimitedTransmit(long transmittedBytes) {
        if (transmittedBytes < 0
                || transmittedBytes > senderMaximumSegmentSize) {
            throw new IllegalArgumentException(
                    "limited transmit must not exceed one SMSS");
        }
        if (duplicateAckCount < 1
                || duplicateAckCount > 2
                || phase == CongestionPhase.FAST_RECOVERY) {
            throw new IllegalStateException(
                    "limited transmit is only valid for the first two duplicate ACKs");
        }
        limitedTransmitBytes = cappedAdd(
                limitedTransmitBytes, transmittedBytes);
    }

    public void onNonDuplicateAcknowledgment() {
        if (phase != CongestionPhase.FAST_RECOVERY) {
            duplicateAckCount = 0;
            limitedTransmitBytes = 0;
        }
    }

    public void onRetransmissionTimeout(
            long flightSize, boolean segmentPreviouslyTimedOut) {
        if (flightSize <= 0) {
            throw new IllegalArgumentException("flightSize must be positive");
        }
        if (!segmentPreviouslyTimedOut) {
            slowStartThreshold = lossThreshold(flightSize);
        }
        congestionWindow = senderMaximumSegmentSize;
        congestionAvoidanceAckedBytes = 0;
        limitedTransmitBytes = 0;
        duplicateAckCount = 0;
        phase = CongestionPhase.SLOW_START;
    }

    public void setCongestionWindow(long congestionWindow) {
        validateWindow(congestionWindow, "congestionWindow");
        this.congestionWindow = congestionWindow;
        congestionAvoidanceAckedBytes = 0;
        limitedTransmitBytes = 0;
        duplicateAckCount = 0;
        phase = congestionWindow < slowStartThreshold
                ? CongestionPhase.SLOW_START
                : CongestionPhase.CONGESTION_AVOIDANCE;
    }

    public void onIdleRestart(long initialWindow) {
        validateWindow(initialWindow, "initialWindow");
        congestionWindow = Math.min(congestionWindow, initialWindow);
        congestionAvoidanceAckedBytes = 0;
        limitedTransmitBytes = 0;
        duplicateAckCount = 0;
        phase = congestionWindow < slowStartThreshold
                ? CongestionPhase.SLOW_START
                : CongestionPhase.CONGESTION_AVOIDANCE;
    }

    public long congestionWindow() {
        return congestionWindow;
    }

    public long slowStartThreshold() {
        return slowStartThreshold;
    }

    public int duplicateAckCount() {
        return duplicateAckCount;
    }

    public CongestionPhase phase() {
        return phase;
    }

    private long lossThreshold(long flightSize) {
        return Math.max(
                flightSize / 2,
                2L * senderMaximumSegmentSize);
    }

    private static long cappedAdd(long left, long right) {
        if (left >= MAX_WINDOW - right) {
            return MAX_WINDOW;
        }
        return left + right;
    }

    private static void validateWindow(long value, String name) {
        if (value < 1 || value > MAX_WINDOW) {
            throw new IllegalArgumentException(
                    name + " must be within the unambiguous sequence range");
        }
    }
}
