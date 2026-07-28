package com.ouc.tcp.timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Owns the single RFC 6298 retransmission timer.
 *
 * <p>The generation check prevents a cancelled callback from affecting a
 * newer timer when the underlying scheduler races with cancellation.</p>
 */
public final class RetransmissionTimer {
    private final Scheduler scheduler;

    private Scheduler.Cancellable scheduled;
    private long generation;
    private boolean running;

    public RetransmissionTimer(Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public synchronized void startOrRestart(Duration delay, Runnable callback) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callback, "callback");
        if (delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("delay must be positive");
        }
        if (scheduled != null) {
            scheduled.cancel();
        }

        long scheduledGeneration = ++generation;
        running = true;
        scheduled = scheduler.schedule(
                delay,
                () -> fireIfCurrent(scheduledGeneration, callback));
    }

    public synchronized void stop() {
        generation++;
        running = false;
        if (scheduled != null) {
            scheduled.cancel();
            scheduled = null;
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    private void fireIfCurrent(long scheduledGeneration, Runnable callback) {
        synchronized (this) {
            if (!running || scheduledGeneration != generation) {
                return;
            }
            running = false;
            scheduled = null;
        }
        callback.run();
    }
}
