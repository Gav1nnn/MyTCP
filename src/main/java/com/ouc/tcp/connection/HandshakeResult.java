package com.ouc.tcp.connection;

import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpSegment;

import java.util.Objects;

/**
 * Sequence and flow-control state negotiated by the three-way handshake.
 */
public record HandshakeResult(
        SequenceNumber32 sendNext,
        SequenceNumber32 receiveNext,
        int peerAdvertisedWindow) {

    public HandshakeResult {
        Objects.requireNonNull(sendNext, "sendNext");
        Objects.requireNonNull(receiveNext, "receiveNext");
        if (peerAdvertisedWindow < 0
                || peerAdvertisedWindow > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException(
                    "peerAdvertisedWindow must be an unsigned 16-bit value");
        }
    }
}
