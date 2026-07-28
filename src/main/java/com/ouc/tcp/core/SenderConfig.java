package com.ouc.tcp.core;

import java.net.Inet4Address;
import java.util.Objects;

/**
 * Immutable established-state sender configuration.
 */
public record SenderConfig(
        Inet4Address localAddress,
        Inet4Address remoteAddress,
        int localPort,
        int remotePort,
        SequenceNumber32 initialSendNext,
        SequenceNumber32 acknowledgmentNumber,
        int localAdvertisedWindow,
        int peerAdvertisedWindow,
        int senderMaximumSegmentSize,
        long initialCongestionWindow) {

    public SenderConfig {
        Objects.requireNonNull(localAddress, "localAddress");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(initialSendNext, "initialSendNext");
        Objects.requireNonNull(acknowledgmentNumber, "acknowledgmentNumber");
        requireUnsigned16(localPort, "localPort");
        requireUnsigned16(remotePort, "remotePort");
        requireUnsigned16(localAdvertisedWindow, "localAdvertisedWindow");
        requireUnsigned16(peerAdvertisedWindow, "peerAdvertisedWindow");
        if (senderMaximumSegmentSize < 1 || senderMaximumSegmentSize > 0xFFFF - 20) {
            throw new IllegalArgumentException(
                    "senderMaximumSegmentSize is outside the IPv4 TCP payload range");
        }
        if (initialCongestionWindow < 1
                || initialCongestionWindow >= SequenceNumber32.HALF_RANGE) {
            throw new IllegalArgumentException(
                    "initialCongestionWindow is outside the unambiguous sequence range");
        }
    }

    private static void requireUnsigned16(int value, String name) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(name + " must be an unsigned 16-bit value");
        }
    }
}
