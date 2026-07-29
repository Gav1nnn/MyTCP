package com.ouc.tcp.connection;

import com.ouc.tcp.core.TcpSegment;

import java.util.List;
import java.util.Objects;

/**
 * Observable result of one connection-lifecycle event.
 */
public record LifecycleResult(
        boolean accepted,
        TcpState previousState,
        TcpState currentState,
        List<TcpSegment> transmissions) {

    public LifecycleResult {
        Objects.requireNonNull(previousState, "previousState");
        Objects.requireNonNull(currentState, "currentState");
        transmissions = List.copyOf(
                Objects.requireNonNull(transmissions, "transmissions"));
    }
}
