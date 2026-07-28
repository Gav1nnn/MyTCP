package com.ouc.tcp.buffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingDataBufferTest {
    @Test
    void preservesFifoOrderAcrossApplicationWrites() {
        PendingDataBuffer buffer = new PendingDataBuffer();
        buffer.append(new byte[] {1, 2});
        buffer.append(new byte[] {3, 4, 5});

        assertArrayEquals(new byte[] {1, 2, 3}, buffer.take(3));
        assertArrayEquals(new byte[] {4, 5}, buffer.take(10));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void protectsQueuedDataFromCallerMutation() {
        PendingDataBuffer buffer = new PendingDataBuffer();
        byte[] data = {1, 2, 3};
        buffer.append(data);

        data[0] = 99;

        assertArrayEquals(new byte[] {1, 2, 3}, buffer.take(3));
    }

    @Test
    void supportsZeroLengthOperations() {
        PendingDataBuffer buffer = new PendingDataBuffer();
        buffer.append(new byte[0]);

        assertArrayEquals(new byte[0], buffer.take(0));
        assertEquals(0, buffer.size());
    }
}
