package com.ouc.tcp.core;

import java.util.Objects;

/**
 * Receive-side subset of the TCP transmission control block.
 */
public final class ReceiveControlBlock {
    private SequenceNumber32 receiveNext;
    private final int receiveBufferCapacity;

    public ReceiveControlBlock(
            SequenceNumber32 initialReceiveNext, int receiveBufferCapacity) {
        this.receiveNext = Objects.requireNonNull(initialReceiveNext, "initialReceiveNext");
        if (receiveBufferCapacity < 1 || receiveBufferCapacity > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException(
                    "receiveBufferCapacity must be between 1 and 65535 bytes");
        }
        this.receiveBufferCapacity = receiveBufferCapacity;
    }

    public SequenceNumber32 receiveNext() {
        return receiveNext;
    }

    public int receiveBufferCapacity() {
        return receiveBufferCapacity;
    }

    public int advertisedWindow() {
        return receiveBufferCapacity;
    }

    void advanceTo(SequenceNumber32 nextExpected) {
        receiveNext = Objects.requireNonNull(nextExpected, "nextExpected");
    }
}
