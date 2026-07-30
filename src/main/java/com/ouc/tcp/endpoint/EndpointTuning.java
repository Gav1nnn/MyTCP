package com.ouc.tcp.endpoint;

/**
 * Data-plane sizing used after a standalone handshake succeeds.
 */
public record EndpointTuning(
        int maximumSegmentSize,
        long initialCongestionWindow,
        long initialSlowStartThreshold) {

    public static EndpointTuning defaults() {
        int mss = 1_200;
        return new EndpointTuning(mss, 3L * mss, 65_535);
    }

    public EndpointTuning {
        if (maximumSegmentSize < 1
                || initialCongestionWindow < 1
                || initialSlowStartThreshold < 1) {
            throw new IllegalArgumentException(
                    "endpoint tuning values must be positive");
        }
    }

    public long initialWindowAfterHandshake(
            boolean localControlRetransmitted) {
        return localControlRetransmitted
                ? Math.min(initialCongestionWindow, maximumSegmentSize)
                : initialCongestionWindow;
    }
}
