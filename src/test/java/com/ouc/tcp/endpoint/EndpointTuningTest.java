package com.ouc.tcp.endpoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndpointTuningTest {
    @Test
    void defaultInitialWindowFollowsRfc5681ForTwelveHundredByteSmss() {
        EndpointTuning tuning = EndpointTuning.defaults();

        assertEquals(1_200, tuning.maximumSegmentSize());
        assertEquals(3_600, tuning.initialCongestionWindow());
        assertEquals(
                3_600,
                tuning.initialWindowAfterHandshake(false));
    }

    @Test
    void retransmittedSynReducesInitialWindowToOneSmss() {
        EndpointTuning tuning = EndpointTuning.defaults();

        assertEquals(
                1_200,
                tuning.initialWindowAfterHandshake(true));
    }
}
