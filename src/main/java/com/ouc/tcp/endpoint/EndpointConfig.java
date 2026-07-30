package com.ouc.tcp.endpoint;

import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpSegment;

import java.net.Inet4Address;
import java.time.Duration;
import java.util.Objects;

/**
 * Established-state configuration for one standalone full-duplex endpoint.
 */
public record EndpointConfig(
        Inet4Address localAddress,
        Inet4Address remoteAddress,
        int localPort,
        int remotePort,
        SequenceNumber32 initialSendNext,
        SequenceNumber32 initialReceiveNext,
        int receiveWindow,
        int peerReceiveWindow,
        int maximumSegmentSize,
        long initialCongestionWindow,
        long initialSlowStartThreshold,
        Duration initialRetransmissionTimeout) {

    public EndpointConfig(
            Inet4Address localAddress,
            Inet4Address remoteAddress,
            int localPort,
            int remotePort,
            SequenceNumber32 initialSendNext,
            SequenceNumber32 initialReceiveNext,
            int receiveWindow,
            int peerReceiveWindow,
            int maximumSegmentSize,
            long initialCongestionWindow,
            long initialSlowStartThreshold) {
        this(
                localAddress,
                remoteAddress,
                localPort,
                remotePort,
                initialSendNext,
                initialReceiveNext,
                receiveWindow,
                peerReceiveWindow,
                maximumSegmentSize,
                initialCongestionWindow,
                initialSlowStartThreshold,
                Duration.ofSeconds(1));
    }

    public EndpointConfig {
        Objects.requireNonNull(localAddress, "localAddress");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(initialSendNext, "initialSendNext");
        Objects.requireNonNull(initialReceiveNext, "initialReceiveNext");
        Objects.requireNonNull(
                initialRetransmissionTimeout,
                "initialRetransmissionTimeout");
        requireUnsigned16(localPort, "localPort");
        requireUnsigned16(remotePort, "remotePort");
        requireUnsigned16(receiveWindow, "receiveWindow");
        requireUnsigned16(peerReceiveWindow, "peerReceiveWindow");
        if (receiveWindow == 0 || maximumSegmentSize < 1) {
            throw new IllegalArgumentException(
                    "receiveWindow and maximumSegmentSize must be positive");
        }
        if (initialCongestionWindow < 1 || initialSlowStartThreshold < 1) {
            throw new IllegalArgumentException(
                    "congestion windows must be positive");
        }
        if (initialRetransmissionTimeout.isZero()
                || initialRetransmissionTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "initialRetransmissionTimeout must be positive");
        }
    }

    private static void requireUnsigned16(int value, String name) {
        if (value < 0 || value > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException(
                    name + " must be an unsigned 16-bit value");
        }
    }
}
