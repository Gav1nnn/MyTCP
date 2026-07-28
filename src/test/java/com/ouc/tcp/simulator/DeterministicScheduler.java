package com.ouc.tcp.simulator;

import com.ouc.tcp.timer.Scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Single-threaded scheduler that executes callbacks only when a test advances
 * its clock.
 */
public final class DeterministicScheduler implements Scheduler {
    private final ManualClock clock;
    private final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<>();
    private long nextOrder;

    public DeterministicScheduler(ManualClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }

        long deadline = Math.addExact(clock.nanoTime(), delay.toNanos());
        ScheduledTask scheduledTask = new ScheduledTask(deadline, nextOrder++, task);
        tasks.add(scheduledTask);
        return scheduledTask;
    }

    public void runReady() {
        advanceTo(clock.nanoTime());
    }

    public void advanceBy(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        advanceTo(Math.addExact(clock.nanoTime(), duration.toNanos()));
    }

    public int pendingTaskCount() {
        return (int) tasks.stream().filter(task -> !task.isCancelled()).count();
    }

    private void advanceTo(long targetNanos) {
        while (!tasks.isEmpty() && tasks.peek().deadlineNanos <= targetNanos) {
            ScheduledTask task = tasks.poll();
            clock.advanceTo(task.deadlineNanos);
            if (!task.cancelled) {
                task.action.run();
            }
        }
        clock.advanceTo(targetNanos);
    }

    private static final class ScheduledTask
            implements Cancellable, Comparable<ScheduledTask> {
        private final long deadlineNanos;
        private final long order;
        private final Runnable action;
        private boolean cancelled;

        private ScheduledTask(long deadlineNanos, long order, Runnable action) {
            this.deadlineNanos = deadlineNanos;
            this.order = order;
            this.action = action;
        }

        @Override
        public boolean cancel() {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public int compareTo(ScheduledTask other) {
            int deadlineComparison = Long.compare(deadlineNanos, other.deadlineNanos);
            if (deadlineComparison != 0) {
                return deadlineComparison;
            }
            return Long.compare(order, other.order);
        }
    }
}
