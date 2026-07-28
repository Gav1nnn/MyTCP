package com.ouc.tcp.timer;

import com.ouc.tcp.simulator.DeterministicScheduler;
import com.ouc.tcp.simulator.ManualClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetransmissionTimerTest {
    @Test
    void firesOnceAtItsDeadline() {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        RetransmissionTimer timer = new RetransmissionTimer(scheduler);
        AtomicInteger firings = new AtomicInteger();

        timer.startOrRestart(Duration.ofSeconds(1), firings::incrementAndGet);
        scheduler.advanceBy(Duration.ofMillis(999));
        assertEquals(0, firings.get());
        assertTrue(timer.isRunning());

        scheduler.advanceBy(Duration.ofMillis(1));
        assertEquals(1, firings.get());
        assertFalse(timer.isRunning());
    }

    @Test
    void restartReplacesThePreviousDeadline() {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        RetransmissionTimer timer = new RetransmissionTimer(scheduler);
        AtomicInteger firings = new AtomicInteger();

        timer.startOrRestart(Duration.ofSeconds(1), firings::incrementAndGet);
        scheduler.advanceBy(Duration.ofMillis(500));
        timer.startOrRestart(Duration.ofSeconds(1), firings::incrementAndGet);
        scheduler.advanceBy(Duration.ofMillis(500));
        assertEquals(0, firings.get());

        scheduler.advanceBy(Duration.ofMillis(500));
        assertEquals(1, firings.get());
    }

    @Test
    void stopCancelsTheActiveTimer() {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        RetransmissionTimer timer = new RetransmissionTimer(scheduler);
        AtomicInteger firings = new AtomicInteger();

        timer.startOrRestart(Duration.ofSeconds(1), firings::incrementAndGet);
        timer.stop();
        scheduler.advanceBy(Duration.ofSeconds(1));

        assertEquals(0, firings.get());
        assertFalse(timer.isRunning());
    }
}
