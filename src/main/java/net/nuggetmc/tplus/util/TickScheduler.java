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
            if (cancelled.remove(task.id())) {
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
