package com.ouc.tcp.connection;

import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpSegment;

import java.net.Inet4Address;
import java.util.Objects;

/**
 * Immutable four-tuple and initial state for a standalone TCP connection.
 */
public record ConnectionConfig(
        Inet4Address localAddress,
        Inet4Address remoteAddress,
        int localPort,
        int remotePort,
        SequenceNumber32 initialSendSequence,
        int receiveWindow) {

    public ConnectionConfig {
        Objects.requireNonNull(localAddress, "localAddress");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(initialSendSequence, "initialSendSequence");
        requirePort(localPort, "localPort");
        requirePort(remotePort, "remotePort");
        if (receiveWindow < 1 || receiveWindow > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException(
                    "receiveWindow must be between 1 and 65535 bytes");
        }
    }

    private static void requirePort(int port, String name) {
        if (port < 0 || port > TcpSegment.MAX_PORT) {
            throw new IllegalArgumentException(
                    name + " must be an unsigned 16-bit value");
        }
    }
}
