package com.ouc.tcp.core;

import java.net.Inet4Address;
import java.util.Objects;

/**
 * Immutable established-state receiver configuration.
 */
public record ReceiverConfig(
        Inet4Address localAddress,
        Inet4Address remoteAddress,
        int localPort,
        int remotePort,
        SequenceNumber32 initialReceiveNext,
        int receiveBufferCapacity) {

    public ReceiverConfig {
        Objects.requireNonNull(localAddress, "localAddress");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(initialReceiveNext, "initialReceiveNext");
        requireUnsigned16(localPort, "localPort");
        requireUnsigned16(remotePort, "remotePort");
        if (receiveBufferCapacity < 1
                || receiveBufferCapacity > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException(
                    "receiveBufferCapacity must be between 1 and 65535 bytes");
        }
    }

    private static void requireUnsigned16(int value, String name) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(
                    name + " must be an unsigned 16-bit value");
        }
    }
}
