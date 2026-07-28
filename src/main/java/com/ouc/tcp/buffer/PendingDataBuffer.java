package com.ouc.tcp.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * FIFO byte buffer for application data not yet assigned sequence numbers.
 */
public final class PendingDataBuffer {
    private final Deque<byte[]> chunks = new ArrayDeque<>();
    private int firstChunkOffset;
    private int size;

    public void append(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return;
        }
        size = Math.addExact(size, data.length);
        chunks.addLast(data.clone());
    }

    public byte[] take(int maximumBytes) {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        int resultLength = Math.min(maximumBytes, size);
        byte[] result = new byte[resultLength];
        int resultOffset = 0;

        while (resultOffset < resultLength) {
            byte[] first = chunks.getFirst();
            int available = first.length - firstChunkOffset;
            int copied = Math.min(available, resultLength - resultOffset);
            System.arraycopy(first, firstChunkOffset, result, resultOffset, copied);
            firstChunkOffset += copied;
            resultOffset += copied;
            size -= copied;

            if (firstChunkOffset == first.length) {
                chunks.removeFirst();
                firstChunkOffset = 0;
            }
        }
        return result;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
