package com.ouc.tcp.buffer;

import com.ouc.tcp.core.SequenceNumber32;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stores received bytes until a contiguous range begins at {@code RCV.NXT}.
 *
 * <p>The first received value for an overlapping byte position is retained.
 * Window checks are performed in the unsigned 32-bit sequence space.</p>
 */
public final class ReassemblyQueue {
    private final Map<Long, Byte> bufferedBytes = new HashMap<>();

    public InsertionResult insert(
            SequenceNumber32 segmentStart,
            byte[] payload,
            SequenceNumber32 leftWindowEdge,
            int windowSize) {
        Objects.requireNonNull(segmentStart, "segmentStart");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(leftWindowEdge, "leftWindowEdge");
        if (windowSize < 0 || windowSize >= SequenceNumber32.HALF_RANGE) {
            throw new IllegalArgumentException(
                    "windowSize must be within the unambiguous sequence range");
        }

        int newBytes = 0;
        int duplicateBytes = 0;
        int outsideWindowBytes = 0;

        for (int offset = 0; offset < payload.length; offset++) {
            SequenceNumber32 position = segmentStart.add(offset);
            long forwardOffset = leftWindowEdge.distanceTo(position);
            if (forwardOffset < windowSize) {
                Byte previous = bufferedBytes.putIfAbsent(position.toLong(), payload[offset]);
                if (previous == null) {
                    newBytes++;
                } else {
                    duplicateBytes++;
                }
            } else if (position.isBefore(leftWindowEdge)) {
                duplicateBytes++;
            } else {
                outsideWindowBytes++;
            }
        }

        return new InsertionResult(newBytes, duplicateBytes, outsideWindowBytes);
    }

    public DrainResult drainContiguous(SequenceNumber32 firstExpected) {
        Objects.requireNonNull(firstExpected, "firstExpected");
        ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        SequenceNumber32 nextExpected = firstExpected;

        Byte value;
        while ((value = bufferedBytes.remove(nextExpected.toLong())) != null) {
            delivered.write(value);
            nextExpected = nextExpected.add(1);
        }

        return new DrainResult(nextExpected, delivered.toByteArray());
    }

    public int bufferedByteCount() {
        return bufferedBytes.size();
    }

    public record InsertionResult(
            int newBytes, int duplicateBytes, int outsideWindowBytes) {
    }

    public static final class DrainResult {
        private final SequenceNumber32 nextExpected;
        private final byte[] deliveredBytes;

        private DrainResult(SequenceNumber32 nextExpected, byte[] deliveredBytes) {
            this.nextExpected = nextExpected;
            this.deliveredBytes = deliveredBytes.clone();
        }

        public SequenceNumber32 nextExpected() {
            return nextExpected;
        }

        public byte[] deliveredBytes() {
            return deliveredBytes.clone();
        }
    }
}
