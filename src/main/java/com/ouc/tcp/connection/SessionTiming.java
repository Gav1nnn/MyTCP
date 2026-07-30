package com.ouc.tcp.connection;

import java.time.Duration;
import java.util.Objects;

/**
 * Connection-lifecycle timing for the standalone transport profile.
 */
public record SessionTiming(Duration maximumSegmentLifetime) {
    private static final Duration LOOPBACK_MSL = Duration.ofMillis(250);

    public static SessionTiming loopbackDefaults() {
        return new SessionTiming(LOOPBACK_MSL);
    }

    public SessionTiming {
        Objects.requireNonNull(
                maximumSegmentLifetime,
                "maximumSegmentLifetime");
        if (maximumSegmentLifetime.isZero()
                || maximumSegmentLifetime.isNegative()) {
            throw new IllegalArgumentException(
                    "maximumSegmentLifetime must be positive");
        }
        try {
            maximumSegmentLifetime.multipliedBy(2);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "maximumSegmentLifetime is too large",
                    overflow);
        }
    }

    public Duration timeWaitDuration() {
        return maximumSegmentLifetime.multipliedBy(2);
    }
}
