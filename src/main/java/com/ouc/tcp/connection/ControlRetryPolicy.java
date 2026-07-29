package com.ouc.tcp.connection;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeout and retry limit for connection-control exchanges.
 */
public record ControlRetryPolicy(Duration timeout, int maximumTimeouts) {
    public ControlRetryPolicy {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maximumTimeouts < 1) {
            throw new IllegalArgumentException(
                    "maximumTimeouts must be positive");
        }
    }
}
