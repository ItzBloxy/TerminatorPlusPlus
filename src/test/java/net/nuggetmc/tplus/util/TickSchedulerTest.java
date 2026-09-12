package net.nuggetmc.tplus.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
