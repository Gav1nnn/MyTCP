package com.ouc.tcp.simulator;

import com.ouc.tcp.core.TcpSegment;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class TransmissionBehaviors {
    private TransmissionBehaviors() {
    }

    public static TransmissionBehavior deliverAfter(Duration delay) {
        requireDelay(delay);
        return segment -> List.of(new ScheduledDelivery(segment, delay));
    }

    public static TransmissionBehavior drop() {
        return segment -> List.of();
    }

    public static TransmissionBehavior duplicate(Duration firstDelay, Duration secondDelay) {
        requireDelay(firstDelay);
        requireDelay(secondDelay);
        return segment -> List.of(
                new ScheduledDelivery(segment, firstDelay),
                new ScheduledDelivery(segment, secondDelay));
    }

    public static TransmissionBehavior corruptPayload(
            int payloadIndex, int xorMask, Duration delay) {
        requireDelay(delay);
        if (payloadIndex < 0) {
            throw new IllegalArgumentException("payloadIndex must not be negative");
        }
        if (xorMask < 1 || xorMask > 0xFF) {
            throw new IllegalArgumentException("xorMask must be between 1 and 255");
        }

        return segment -> {
            byte[] corrupted = segment.payload();
            if (payloadIndex >= corrupted.length) {
                throw new IllegalArgumentException("payloadIndex is outside the payload");
            }
            corrupted[payloadIndex] ^= (byte) xorMask;
            return List.of(new ScheduledDelivery(segment.withPayload(corrupted), delay));
        };
    }

    private static void requireDelay(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
    }
}
