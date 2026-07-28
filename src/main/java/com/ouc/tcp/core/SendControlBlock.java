package com.ouc.tcp.core;

import java.util.Objects;

/**
 * Send-side subset of the TCP transmission control block.
 */
public final class SendControlBlock {
    private SequenceNumber32 sendUnacknowledged;
    private SequenceNumber32 sendNext;
    private int sendWindow;
    private long congestionWindow;
    private SequenceNumber32 sendWindowUpdateSequence = SequenceNumber32.of(0);
    private SequenceNumber32 sendWindowUpdateAck = SequenceNumber32.of(0);
    private boolean windowUpdateInitialized;

    public SendControlBlock(
            SequenceNumber32 initialSendNext,
            int initialSendWindow,
            long initialCongestionWindow) {
        this.sendUnacknowledged =
                Objects.requireNonNull(initialSendNext, "initialSendNext");
        this.sendNext = initialSendNext;
        validateSendWindow(initialSendWindow);
        validateCongestionWindow(initialCongestionWindow);
        this.sendWindow = initialSendWindow;
        this.congestionWindow = initialCongestionWindow;
    }

    public SequenceNumber32 sendUnacknowledged() {
        return sendUnacknowledged;
    }

    public SequenceNumber32 sendNext() {
        return sendNext;
    }

    public int sendWindow() {
        return sendWindow;
    }

    public long congestionWindow() {
        return congestionWindow;
    }

    public long flightSize() {
        return sendUnacknowledged.distanceTo(sendNext);
    }

    public long usableWindow() {
        long effectiveWindow = Math.min(congestionWindow, sendWindow);
        return Math.max(0, effectiveWindow - flightSize());
    }

    public void advanceSendNext(int byteCount) {
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
        sendNext = sendNext.add(byteCount);
    }

    public void advanceSendUnacknowledged(SequenceNumber32 acknowledgmentNumber) {
        Objects.requireNonNull(acknowledgmentNumber, "acknowledgmentNumber");
        if (!sendUnacknowledged.isBefore(acknowledgmentNumber)
                || !acknowledgmentNumber.isBeforeOrEqual(sendNext)) {
            throw new IllegalArgumentException("acknowledgment is outside outstanding data");
        }
        sendUnacknowledged = acknowledgmentNumber;
    }

    public boolean updateSendWindow(
            SequenceNumber32 segmentSequence,
            SequenceNumber32 segmentAck,
            int advertisedWindow) {
        Objects.requireNonNull(segmentSequence, "segmentSequence");
        Objects.requireNonNull(segmentAck, "segmentAck");
        validateSendWindow(advertisedWindow);

        boolean isNewer = !windowUpdateInitialized
                || sendWindowUpdateSequence.isBefore(segmentSequence)
                || (sendWindowUpdateSequence.equals(segmentSequence)
                        && sendWindowUpdateAck.isBeforeOrEqual(segmentAck));
        if (!isNewer) {
            return false;
        }

        sendWindow = advertisedWindow;
        sendWindowUpdateSequence = segmentSequence;
        sendWindowUpdateAck = segmentAck;
        windowUpdateInitialized = true;
        return true;
    }

    public void setCongestionWindow(long congestionWindow) {
        validateCongestionWindow(congestionWindow);
        this.congestionWindow = congestionWindow;
    }

    private static void validateSendWindow(int sendWindow) {
        if (sendWindow < 0 || sendWindow > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException("sendWindow must be an unsigned 16-bit value");
        }
    }

    private static void validateCongestionWindow(long congestionWindow) {
        if (congestionWindow < 1 || congestionWindow >= SequenceNumber32.HALF_RANGE) {
            throw new IllegalArgumentException(
                    "congestionWindow must be within the unambiguous sequence range");
        }
    }
}
