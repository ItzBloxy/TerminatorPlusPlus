package net.nuggetmc.tplus.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A tick-keyed delayed-task queue, replacing {@code BukkitScheduler}'s
 * {@code runTaskLater}. Driven once per server tick from {@code BotRegistry}.
 *
 * <p>Not thread-safe by design: every caller runs on the server thread. Work
 * arriving from another thread must be marshalled with {@code server.execute}
 * before touching this class.
 */
public final class TickScheduler {

    // Its own logger rather than TerminatorPlus.LOGGER: this class is unit tested with
    // no game running, and it should not drag the @Mod class into a pure test.
    private static final Logger LOGGER = LoggerFactory.getLogger(TickScheduler.class);

    private record Task(int id, Runnable action) {
    }

    private final TreeMap<Long, List<Task>> queue = new TreeMap<>();
    private final Set<Integer> cancelled = new HashSet<>();

    private long currentTick;
    private int nextId = 1;

    /**
     * Schedules {@code action} to run {@code delayTicks} from now. A delay of zero
     * or less runs on the next tick, never inline.
     *
     * @return an id usable with {@link #cancel(int)}
     */
    public int runLater(long delayTicks, Runnable action) {
        int id = nextId++;
        long due = currentTick + Math.max(1, delayTicks);
        queue.computeIfAbsent(due, k -> new ArrayList<>()).add(new Task(id, action));
        return id;
    }

    public void cancel(int id) {
        cancelled.add(id);
    }

    /** Whether {@link #cancel(int)} has been called for {@code id}. */
    public boolean isCancelled(int id) {
        return cancelled.contains(id);
    }

    /**
     * Schedules {@code action} to run every {@code periodTicks}, starting one period from now.
     *
     * <p>Replaces {@code BukkitRunnable.runTaskTimer(plugin, 0, period)}. Two differences, both
     * deliberate: the first run is one period away rather than immediate, because nothing in
     * this class runs inline; and the id stays stable across repeats, so a single
     * {@link #cancel(int)} stops the task for good.
     *
     * <p>Expressed as a task that reschedules itself, which reuses the cancellation set and the
     * per-task error isolation in {@link #tick()} rather than adding a second mechanism. A
     * throwing run is logged there and the task is still re-armed — the swing animation in
     * {@code Mining} must survive one bad tick.
     *
     * @return an id usable with {@link #cancel(int)}
     */
    public int runRepeating(long periodTicks, Runnable action) {
        int id = nextId++;
        schedule(id, Math.max(1, periodTicks), action);
        return id;
    }

    private void schedule(int id, long period, Runnable action) {
        long due = currentTick + period;

        queue.computeIfAbsent(due, k -> new ArrayList<>()).add(new Task(id, () -> {
            try {
                action.run();
            } finally {
                // In a finally block so a throwing action does not end the repetition. The
                // cancellation check in tick() is what stops it.
                if (!cancelled.contains(id)) {
                    schedule(id, period, action);
                }
            }
        }));
    }

    public void cancelAll() {
        queue.clear();
        cancelled.clear();
    }

    /** Advances one tick and runs everything now due. */
    public void tick() {
        currentTick++;

        // Drain by whole buckets so a task scheduled from inside a task lands on a
        // later tick rather than extending this one.
        List<Task> due = new ArrayList<>();
        Iterator<Map.Entry<Long, List<Task>>> it = queue.headMap(currentTick, true).entrySet().iterator();
        while (it.hasNext()) {
            due.addAll(it.next().getValue());
            it.remove();
        }

        for (Task task : due) {
            // contains, not remove. Consuming the flag is fine for a one-shot but would let a
            // repeating task resume after one skipped run, because its id comes back every
            // period. The set therefore grows over a long session; it is bounded in practice by
            // cancelAll on every reset, and the ids are ints, so no reaper is worth building.
            if (cancelled.contains(task.id())) {
                continue;
            }
            try {
                task.action().run();
            } catch (Throwable t) {
                // One bad task must not stop the rest, nor the server tick.
                LOGGER.error("Scheduled TerminatorPlus task {} failed", task.id(), t);
            }
        }
    }
}
