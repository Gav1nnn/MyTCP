package com.ouc.tcp.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
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
        byte[] result = copyPrefix(maximumBytes);
        discard(result.length);
        return result;
    }

    public byte[] peek(int maximumBytes) {
        return copyPrefix(maximumBytes);
    }

    private byte[] copyPrefix(int maximumBytes) {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        int resultLength = Math.min(maximumBytes, size);
        byte[] result = new byte[resultLength];
        int resultOffset = 0;
        Iterator<byte[]> iterator = chunks.iterator();
        int chunkOffset = firstChunkOffset;

        while (resultOffset < resultLength) {
            byte[] chunk = iterator.next();
            int available = chunk.length - chunkOffset;
            int copied = Math.min(available, resultLength - resultOffset);
            System.arraycopy(chunk, chunkOffset, result, resultOffset, copied);
            chunkOffset += copied;
            resultOffset += copied;

            if (chunkOffset == chunk.length) {
                chunkOffset = 0;
            }
        }
        return result;
    }

    private void discard(int byteCount) {
        int remaining = byteCount;
        while (remaining > 0) {
            byte[] first = chunks.getFirst();
            int available = first.length - firstChunkOffset;
            int discarded = Math.min(available, remaining);
            firstChunkOffset += discarded;
            remaining -= discarded;
            size -= discarded;

            if (firstChunkOffset == first.length) {
                chunks.removeFirst();
                firstChunkOffset = 0;
            }
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
