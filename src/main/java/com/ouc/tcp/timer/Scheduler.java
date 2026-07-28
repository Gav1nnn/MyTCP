package com.ouc.tcp.timer;

import java.time.Duration;

/**
 * Schedules one-shot protocol callbacks.
 */
public interface Scheduler {
    Cancellable schedule(Duration delay, Runnable task);

    interface Cancellable {
        boolean cancel();

        boolean isCancelled();
    }
}
