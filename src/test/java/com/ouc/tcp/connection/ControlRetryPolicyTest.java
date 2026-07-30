package com.ouc.tcp.connection;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlRetryPolicyTest {
    @Test
    void doublesTimeoutAndCapsItAtSixtySeconds() {
        ControlRetryPolicy policy =
                new ControlRetryPolicy(Duration.ofSeconds(1), 8);

        assertEquals(
                Duration.ofSeconds(2),
                policy.backOff(Duration.ofSeconds(1)));
        assertEquals(
                Duration.ofSeconds(60),
                policy.backOff(Duration.ofSeconds(40)));
    }
}
