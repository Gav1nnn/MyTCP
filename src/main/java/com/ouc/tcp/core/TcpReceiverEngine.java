package com.ouc.tcp.core;

import com.ouc.tcp.buffer.ReassemblyQueue;
import com.ouc.tcp.checksum.TcpChecksum;

import java.util.Objects;

/**
 * Established-state TCP receive path for reliable, ordered byte delivery.
 */
public final class TcpReceiverEngine {
    private final ReceiveControlBlock controlBlock;
    private final ReassemblyQueue reassemblyQueue;

    public TcpReceiverEngine(
            SequenceNumber32 initialReceiveNext, int receiveBufferCapacity) {
        this.controlBlock =
                new ReceiveControlBlock(initialReceiveNext, receiveBufferCapacity);
        this.reassemblyQueue = new ReassemblyQueue();
    }

    public synchronized ReceiveResult receive(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");

        if (!hasValidChecksum(segment)) {
            return result(ReceiveDisposition.CHECKSUM_FAILED, new byte[0], false);
        }

        byte[] payload = segment.payload();
        if (payload.length == 0) {
            return result(ReceiveDisposition.NO_DATA, new byte[0], false);
        }

        SequenceNumber32 segmentStart = SequenceNumber32.of(segment.sequenceNumber());
        SequenceNumber32 segmentEnd = segmentStart.add(payload.length - 1L);
        if (!isWithinReceiveWindow(segmentStart)
                && !isWithinReceiveWindow(segmentEnd)) {
            ReceiveDisposition disposition = segmentEnd.isBefore(controlBlock.receiveNext())
                    ? ReceiveDisposition.DUPLICATE
                    : ReceiveDisposition.OUTSIDE_WINDOW;
            return result(disposition, new byte[0], true);
        }

        ReassemblyQueue.InsertionResult insertion = reassemblyQueue.insert(
                segmentStart,
                payload,
                controlBlock.receiveNext(),
                controlBlock.advertisedWindow());
        ReassemblyQueue.DrainResult drain =
                reassemblyQueue.drainContiguous(controlBlock.receiveNext());
        controlBlock.advanceTo(drain.nextExpected());

        byte[] deliveredBytes = drain.deliveredBytes();
        ReceiveDisposition disposition = classify(insertion, deliveredBytes.length);
        return result(disposition, deliveredBytes, true);
    }

    public synchronized SequenceNumber32 receiveNext() {
        return controlBlock.receiveNext();
    }

    public synchronized int advertisedWindow() {
        return controlBlock.advertisedWindow();
    }

    public synchronized int bufferedByteCount() {
        return reassemblyQueue.bufferedByteCount();
    }

    private ReceiveResult result(
            ReceiveDisposition disposition,
            byte[] deliveredBytes,
            boolean acknowledgmentRequired) {
        return new ReceiveResult(
                disposition,
                controlBlock.receiveNext(),
                controlBlock.advertisedWindow(),
                deliveredBytes,
                acknowledgmentRequired);
    }

    private boolean isWithinReceiveWindow(SequenceNumber32 sequenceNumber) {
        return controlBlock.receiveNext().distanceTo(sequenceNumber)
                < controlBlock.advertisedWindow();
    }

    private static boolean hasValidChecksum(TcpSegment segment) {
        try {
            return TcpChecksum.isValid(segment);
        } catch (IllegalArgumentException malformedSegment) {
            return false;
        }
    }

    private static ReceiveDisposition classify(
            ReassemblyQueue.InsertionResult insertion, int deliveredByteCount) {
        if (deliveredByteCount > 0) {
            return ReceiveDisposition.IN_ORDER;
        }
        if (insertion.newBytes() > 0) {
            return ReceiveDisposition.OUT_OF_ORDER;
        }
        if (insertion.duplicateBytes() > 0) {
            return ReceiveDisposition.DUPLICATE;
        }
        return ReceiveDisposition.OUTSIDE_WINDOW;
    }
}
