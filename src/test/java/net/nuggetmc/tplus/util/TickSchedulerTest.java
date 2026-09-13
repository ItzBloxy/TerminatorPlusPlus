package net.nuggetmc.tplus.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TickSchedulerTest {

    @Test
    void taskRunsOnTheScheduledTickAndNotBefore() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(3, () -> log.add("fired"));

        scheduler.tick();
        scheduler.tick();
        assertTrue(log.isEmpty(), "must not fire early");

        scheduler.tick();
        assertEquals(List.of("fired"), log);
    }

    @Test
    void zeroDelayRunsOnTheNextTick() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(0, () -> log.add("fired"));
        scheduler.tick();

        assertEquals(List.of("fired"), log);
    }

    @Test
    void tasksDueOnTheSameTickAllRunInSubmissionOrder() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> log.add("a"));
        scheduler.runLater(1, () -> log.add("b"));
        scheduler.runLater(1, () -> log.add("c"));
        scheduler.tick();

        assertEquals(List.of("a", "b", "c"), log);
    }

    @Test
    void aTaskRunsOnlyOnce() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> log.add("fired"));
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertEquals(1, log.size());
    }

    @Test
    void cancelledTaskNeverRuns() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        int id = scheduler.runLater(2, () -> log.add("fired"));
        scheduler.cancel(id);
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertTrue(log.isEmpty());
    }

    @Test
    void cancelAllClearsEverythingPending() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> log.add("a"));
        scheduler.runLater(5, () -> log.add("b"));
        scheduler.cancelAll();
        for (int i = 0; i < 10; i++) scheduler.tick();

        assertTrue(log.isEmpty());
    }

    @Test
    void aTaskScheduledFromInsideATaskRunsOnALaterTick() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> {
            log.add("outer");
            scheduler.runLater(1, () -> log.add("inner"));
        });

        scheduler.tick();
        assertEquals(List.of("outer"), log, "nested task must not run in the same tick");

        scheduler.tick();
        assertEquals(List.of("outer", "inner"), log);
    }

    @Test
    void oneThrowingTaskDoesNotPreventOthersFromRunning() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> { throw new IllegalStateException("boom"); });
        scheduler.runLater(1, () -> log.add("survivor"));

        assertDoesNotThrow(scheduler::tick);
        assertEquals(List.of("survivor"), log);
    }

    @Test
    void negativeDelayIsTreatedAsNextTick() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(-5, () -> log.add("fired"));
        scheduler.tick();

        assertEquals(List.of("fired"), log);
    }

    // ---- repeating tasks ----------------------------------------------------

    @Test
    void aRepeatingTaskRunsEveryPeriod() {
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        scheduler.runRepeating(2, runs::incrementAndGet);

        // Upstream's runTaskTimer(plugin, 0, 2) fires immediately and then every 2 ticks. Ours
        // cannot fire inline — nothing in TickScheduler ever does, by design — so the first run
        // is the next period. Mining's swing cadence is the only thing that notices, and one
        // tick of swing delay is invisible.
        for (int i = 0; i < 6; i++) {
            scheduler.tick();
        }

        assertEquals(3, runs.get(), "ticks 2, 4 and 6");
    }

    @Test
    void aCancelledRepeatingTaskStops() {
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        int id = scheduler.runRepeating(1, runs::incrementAndGet);

        scheduler.tick();
        scheduler.tick();
        scheduler.cancel(id);
        scheduler.tick();
        scheduler.tick();

        assertEquals(2, runs.get(), "no runs after cancellation");
    }

    @Test
    void aRepeatingTaskThatThrowsKeepsRunning() {
        // Mining's swing task touches the world every 4 ticks forever. One bad tick must not
        // silently end the animation, or a bot mines invisibly.
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        scheduler.runRepeating(1, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        scheduler.tick();
        scheduler.tick();

        assertEquals(2, runs.get(), "a throwing repeating task must be rescheduled");
    }

    @Test
    void cancelAllStopsRepeatingTasksToo() {
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        scheduler.runRepeating(1, runs::incrementAndGet);
        scheduler.tick();
        scheduler.cancelAll();
        scheduler.tick();
        scheduler.tick();

        assertEquals(1, runs.get());
    }

    @Test
    void aCancelledOneShotStaysCancelled() {
        // Cancellation used to be consumed on read, which is fine for a one-shot but would let
        // a repeating task resume after one skipped run. The flag is persistent now, so this
        // pins that a one-shot still never runs.
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        int id = scheduler.runLater(2, runs::incrementAndGet);
        scheduler.cancel(id);

        for (int i = 0; i < 5; i++) {
            scheduler.tick();
        }

        assertEquals(0, runs.get());
    }
}
