package com.ouc.tcp.timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Single-threaded production scheduler for protocol timers.
 */
public final class ExecutorScheduler implements Scheduler, AutoCloseable {
    private final ScheduledExecutorService executor;

    public ExecutorScheduler(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        ScheduledFuture<?> future = executor.schedule(
                task, delay.toNanos(), TimeUnit.NANOSECONDS);
        return new FutureCancellable(future);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record FutureCancellable(ScheduledFuture<?> future)
            implements Cancellable {
        private FutureCancellable {
            Objects.requireNonNull(future, "future");
        }

        @Override
        public boolean cancel() {
            return future.cancel(false);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }
}
