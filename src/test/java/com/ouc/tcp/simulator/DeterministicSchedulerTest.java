package com.ouc.tcp.simulator;

import com.ouc.tcp.timer.Scheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicSchedulerTest {
    @Test
    void executesTasksByDeadlineAndInsertionOrder() {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        List<String> events = new ArrayList<>();

        scheduler.schedule(Duration.ofMillis(20), () -> events.add("third"));
        scheduler.schedule(Duration.ofMillis(10), () -> events.add("first"));
        scheduler.schedule(Duration.ofMillis(10), () -> events.add("second"));

        scheduler.advanceBy(Duration.ofMillis(9));
        assertEquals(List.of(), events);

        scheduler.advanceBy(Duration.ofMillis(1));
        assertEquals(List.of("first", "second"), events);
        assertEquals(Duration.ofMillis(10).toNanos(), clock.nanoTime());

        scheduler.advanceBy(Duration.ofMillis(10));
        assertEquals(List.of("first", "second", "third"), events);
        assertEquals(0, scheduler.pendingTaskCount());
    }

    @Test
    void cancelledTaskNeverRuns() {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        List<String> events = new ArrayList<>();

        Scheduler.Cancellable task =
                scheduler.schedule(Duration.ofSeconds(1), () -> events.add("unexpected"));

        assertTrue(task.cancel());
        assertTrue(task.isCancelled());
        assertFalse(task.cancel());
        assertEquals(0, scheduler.pendingTaskCount());

        scheduler.advanceBy(Duration.ofSeconds(1));
        assertEquals(List.of(), events);
    }

    @Test
    void executesTasksScheduledByCallbacksAtTheSameTimestamp() {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        List<String> events = new ArrayList<>();

        scheduler.schedule(Duration.ofMillis(5), () -> {
            events.add("outer");
            scheduler.schedule(Duration.ZERO, () -> events.add("inner"));
        });

        scheduler.advanceBy(Duration.ofMillis(5));
        assertEquals(List.of("outer", "inner"), events);
    }

    @Test
    void rejectsNegativeTimeMovement() {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);

        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(Duration.ofNanos(-1), () -> { }));
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.advanceBy(Duration.ofNanos(-1)));
    }
}
