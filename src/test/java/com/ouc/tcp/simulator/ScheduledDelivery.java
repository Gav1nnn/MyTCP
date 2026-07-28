package com.ouc.tcp.simulator;

import com.ouc.tcp.core.TcpSegment;

import java.time.Duration;
import java.util.Objects;

public record ScheduledDelivery(TcpSegment segment, Duration delay) {
    public ScheduledDelivery {
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
    }
}
