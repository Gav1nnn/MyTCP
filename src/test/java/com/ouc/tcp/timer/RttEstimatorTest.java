package com.ouc.tcp.timer;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RttEstimatorTest {
    @Test
    void startsWithOneSecondRtoAndNoMeasurements() {
        RttEstimator estimator = new RttEstimator();

        assertEquals(Duration.ofSeconds(1), estimator.retransmissionTimeout());
        assertTrue(estimator.smoothedRtt().isEmpty());
        assertTrue(estimator.rttVariation().isEmpty());
    }

    @Test
    void initializesAndUpdatesRfc6298StateInTheRequiredOrder() {
        RttEstimator estimator = new RttEstimator();

        estimator.recordSample(Duration.ofSeconds(2));
        assertEquals(Duration.ofSeconds(2), estimator.smoothedRtt().orElseThrow());
        assertEquals(Duration.ofSeconds(1), estimator.rttVariation().orElseThrow());
        assertEquals(Duration.ofSeconds(6), estimator.retransmissionTimeout());

        estimator.recordSample(Duration.ofSeconds(2));
        assertEquals(Duration.ofSeconds(2), estimator.smoothedRtt().orElseThrow());
        assertEquals(Duration.ofMillis(750), estimator.rttVariation().orElseThrow());
        assertEquals(Duration.ofSeconds(5), estimator.retransmissionTimeout());
    }

    @Test
    void clampsSmallComputedRtoToOneSecond() {
        RttEstimator estimator = new RttEstimator();

        estimator.recordSample(Duration.ofMillis(100));

        assertEquals(Duration.ofSeconds(1), estimator.retransmissionTimeout());
    }

    @Test
    void exponentialBackoffStopsAtConfiguredMaximum() {
        RttEstimator estimator = new RttEstimator();

        for (int attempt = 0; attempt < 10; attempt++) {
            estimator.backOff();
        }

        assertEquals(Duration.ofSeconds(60), estimator.retransmissionTimeout());
    }

    @Test
    void freshMeasurementRecomputesRtoAfterBackoff() {
        RttEstimator estimator = new RttEstimator();
        estimator.backOff();
        estimator.backOff();

        estimator.recordSample(Duration.ofMillis(100));

        assertEquals(Duration.ofSeconds(1), estimator.retransmissionTimeout());
    }
}
