package com.ouc.tcp.connection;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeout and retry limit for connection-control exchanges.
 */
public record ControlRetryPolicy(Duration timeout, int maximumTimeouts) {
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(60);

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

    public Duration backOff(Duration currentTimeout) {
        Objects.requireNonNull(currentTimeout, "currentTimeout");
        if (currentTimeout.isZero() || currentTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "currentTimeout must be positive");
        }
        Duration doubled;
        try {
            doubled = currentTimeout.multipliedBy(2);
        } catch (ArithmeticException overflow) {
            return MAXIMUM_TIMEOUT;
        }
        return doubled.compareTo(MAXIMUM_TIMEOUT) > 0
                ? MAXIMUM_TIMEOUT
                : doubled;
    }
}
