package com.ouc.tcp.endpoint;

import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.TcpSegment;

import java.util.Objects;

/**
 * Application-visible result of one standalone session receive event.
 */
public record SessionEvent(
        TcpSegment receivedSegment,
        byte[] deliveredBytes,
        TcpState state) {

    public SessionEvent {
        Objects.requireNonNull(receivedSegment, "receivedSegment");
        deliveredBytes = Objects.requireNonNull(
                deliveredBytes, "deliveredBytes").clone();
        Objects.requireNonNull(state, "state");
    }

    @Override
    public byte[] deliveredBytes() {
        return deliveredBytes.clone();
    }
}
