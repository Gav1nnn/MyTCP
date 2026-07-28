package com.ouc.tcp.timer;

/**
 * Monotonic time source used by protocol timers and RTT measurement.
 */
@FunctionalInterface
public interface Clock {
    long nanoTime();

    static Clock system() {
        return System::nanoTime;
    }
}
