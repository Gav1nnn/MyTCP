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
    private final ReceiverConfig config;

    public TcpReceiverEngine(
            SequenceNumber32 initialReceiveNext, int receiveBufferCapacity) {
        config = null;
        this.controlBlock =
                new ReceiveControlBlock(initialReceiveNext, receiveBufferCapacity);
        this.reassemblyQueue = new ReassemblyQueue();
    }

    public TcpReceiverEngine(ReceiverConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        controlBlock = new ReceiveControlBlock(
                config.initialReceiveNext(), config.receiveBufferCapacity());
        reassemblyQueue = new ReassemblyQueue();
    }

    public synchronized ReceiveResult receive(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");

        if (!matchesConnection(segment)) {
            return result(ReceiveDisposition.WRONG_CONNECTION, new byte[0], false);
        }
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

    public synchronized SegmentAcceptability segmentAcceptability(
            TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        if (!matchesConnection(segment)) {
            return SegmentAcceptability.WRONG_CONNECTION;
        }
        if (!hasValidChecksum(segment)) {
            return SegmentAcceptability.CHECKSUM_FAILED;
        }

        int sequenceLength = segment.sequenceSpaceLength();
        int window = controlBlock.advertisedWindow();
        SequenceNumber32 start =
                SequenceNumber32.of(segment.sequenceNumber());
        if (sequenceLength == 0) {
            boolean acceptable = window == 0
                    ? start.equals(controlBlock.receiveNext())
                    : isWithinReceiveWindow(start);
            return acceptable
                    ? SegmentAcceptability.ACCEPTABLE
                    : SegmentAcceptability.OUTSIDE_WINDOW;
        }
        if (window == 0) {
            return SegmentAcceptability.OUTSIDE_WINDOW;
        }
        SequenceNumber32 end = start.add(sequenceLength - 1L);
        return isWithinReceiveWindow(start)
                        || isWithinReceiveWindow(end)
                ? SegmentAcceptability.ACCEPTABLE
                : SegmentAcceptability.OUTSIDE_WINDOW;
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
                advertisedWindow(),
                deliveredBytes,
                acknowledgmentRequired);
    }

    private boolean isWithinReceiveWindow(SequenceNumber32 sequenceNumber) {
        return controlBlock.receiveNext().distanceTo(sequenceNumber)
                < controlBlock.advertisedWindow();
    }

    private boolean matchesConnection(TcpSegment segment) {
        return config == null
                || (segment.sourceAddress().equals(config.remoteAddress())
                        && segment.destinationAddress().equals(config.localAddress())
                        && segment.sourcePort() == config.remotePort()
                        && segment.destinationPort() == config.localPort());
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
