package com.ouc.tcp.connection;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionTimingTest {
    @Test
    void timeWaitLastsTwiceTheMaximumSegmentLifetime() {
        SessionTiming timing =
                new SessionTiming(Duration.ofMillis(25));

        assertEquals(
                Duration.ofMillis(50),
                timing.timeWaitDuration());
    }

    @Test
    void rejectsInvalidMaximumSegmentLifetime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SessionTiming(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SessionTiming(
                        Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
