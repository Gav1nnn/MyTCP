package com.ouc.tcp.trace;

import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpSegment;

import java.time.Duration;
import java.util.Objects;

/**
 * Observable RFC send-control variables at one protocol event boundary.
 */
public record SenderSnapshot(
        SequenceNumber32 sendUnacknowledged,
        SequenceNumber32 sendNext,
        long flightSize,
        long congestionWindow,
        long slowStartThreshold,
        int sendWindow,
        Duration retransmissionTimeout) {

    public SenderSnapshot {
        Objects.requireNonNull(
                sendUnacknowledged,
                "sendUnacknowledged");
        Objects.requireNonNull(sendNext, "sendNext");
        Objects.requireNonNull(
                retransmissionTimeout,
                "retransmissionTimeout");
        if (flightSize < 0
                || congestionWindow < 1
                || slowStartThreshold < 1
                || sendWindow < 0
                || sendWindow > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException(
                    "sender snapshot contains an invalid window value");
        }
    }
}
