package com.ouc.tcp.simulator;

import com.ouc.tcp.timer.Clock;

/**
 * Monotonic clock controlled by deterministic tests.
 */
public final class ManualClock implements Clock {
    private long nowNanos;

    @Override
    public long nanoTime() {
        return nowNanos;
    }

    void advanceTo(long targetNanos) {
        if (targetNanos < nowNanos) {
            throw new IllegalArgumentException("manual clock cannot move backwards");
        }
        nowNanos = targetNanos;
    }
}
