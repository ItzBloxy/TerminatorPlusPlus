package net.nuggetmc.tplus.agent;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.event.BotDeathEvent;
import net.nuggetmc.tplus.event.BotFallDamageEvent;
import net.nuggetmc.tplus.event.BotKilledByPlayerEvent;
import net.nuggetmc.tplus.util.TickScheduler;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * What drives a bot's decisions. Ported from {@code api/agent/Agent}.
 *
 * <p>Three things changed in the translation, all of them because Bukkit's scheduler is gone.
 *
 * <p><b>There is no repeating task.</b> Upstream's {@code setEnabled(true)} called
 * {@code scheduleSyncRepeatingTask(plugin, this::tick, 0, 1)} and {@code setEnabled(false)}
 * cancelled it. {@code BotRegistry.tick()} already runs every server tick and already isolates
 * each bot, so enabling is a flag it consults rather than a task to schedule. Same cadence, and
 * the per-bot isolation from spec §7 survives, which a self-scheduling agent would have bypassed.
 *
 * <p><b>Tasks are ids, not runnables.</b> {@code TickScheduler} hands out ints, so
 * {@code taskList} holds ints and {@code stopAllTasks} cancels them.
 *
 * <p><b>{@code onBotKilledByPlayer} runs on the server thread.</b> Upstream wrapped a registry
 * lookup and an integer increment in {@code runTaskAsynchronously}, which bought nothing and
 * raced with the registry. Nothing observable changes.
 */
public abstract class Agent {

    protected final @Nullable BotRegistry registry;
    protected final Set<Integer> taskList = new HashSet<>();
    protected final Random random = new Random();

    protected boolean enabled;
    protected boolean drops;

    protected Agent(@Nullable BotRegistry registry) {
        this.registry = registry;
        setEnabled(true);
    }

    /** A do-nothing agent, so callers never have to null-check {@link Bot#agent()}. */
    public static Agent noop(@Nullable BotRegistry registry) {
        return new Agent(registry) {
            @Override
            public void tick() {
            }
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean b) {
        enabled = b;

        if (!b) {
            stopAllTasks();
        }
    }

    public void setDrops(boolean enabled) {
        this.drops = enabled;
    }

    public boolean isDrops() {
        return drops;
    }

    /**
     * Schedules {@code action} and remembers the id so {@link #stopAllTasks()} can cancel it.
     *
     * <p>Every delayed call in the agent goes through here. Upstream had 27 bare
     * {@code runTaskLater} calls and only added some of them to {@code taskList}, which is why
     * disabling the agent used to leave swing animations running.
     *
     * <p>Public rather than protected: {@code Mining}, {@code Navigation} and {@code BlockScan}
     * all schedule, and they are in another package. A one-method interface between classes that
     * ship together would be ceremony.
     */
    public int later(long delayTicks, Runnable action) {
        if (registry == null) {
            return -1;
        }

        // A one-element array because the id is only known once runLater has returned, and the
        // action needs it to drop itself. Without that, taskList only ever grows: a bot mining
        // sideways schedules one of these every tick it is blocked, and upstream's set had the
        // same flaw over a smaller set of call sites, since only some of its 27 delayed calls
        // were registered at all.
        int[] id = new int[1];

        id[0] = registry.scheduler().runLater(delayTicks, () -> {
            try {
                action.run();
            } finally {
                taskList.remove(id[0]);
            }
        });

        taskList.add(id[0]);
        return id[0];
    }

    /** Schedules a repeating {@code action} and remembers the id. */
    public int repeating(long periodTicks, Runnable action) {
        if (registry == null) {
            return -1;
        }

        int id = registry.scheduler().runRepeating(periodTicks, action);
        taskList.add(id);
        return id;
    }

    /** Cancels a task this agent scheduled and forgets its id. */
    public void cancel(int id) {
        TickScheduler scheduler = scheduler();

        if (scheduler != null) {
            scheduler.cancel(id);
        }

        taskList.remove(id);
    }

    /** The shared randomness source. {@code Mining} uses it for crack-animation ids. */
    public Random random() {
        return random;
    }

    protected @Nullable TickScheduler scheduler() {
        return registry == null ? null : registry.scheduler();
    }

    public void stopAllTasks() {
        TickScheduler scheduler = scheduler();

        if (scheduler != null) {
            taskList.forEach(scheduler::cancel);
        }

        taskList.clear();
    }

    /**
     * Called once per server tick before {@link #tickBot(Bot)} runs for any bot.
     *
     * <p>Upstream's {@code tick()} computed {@code botsInPlayerList} and then looped over every
     * bot itself. The loop lives in {@code BotRegistry} so that one bot throwing cannot stop the
     * others, so the per-tick setup splits out here.
     *
     * <p>Public, where upstream's was protected: upstream's agent scheduled its own repeating
     * task and called this on itself, and ours is driven by the registry from another package.
     */
    public abstract void tick();

    /** Called once per live bot per tick, from {@code BotRegistry.tickBot}. */
    public void tickBot(Bot bot) {
    }

    public void onFallDamage(BotFallDamageEvent event) {
    }

    public void onPlayerDamage(BotDamageByPlayerEvent event) {
    }

    public void onBotDeath(BotDeathEvent event) {
    }

    /**
     * Credits a kill. If the killer is itself driving a bot, that bot's tally goes up.
     *
     * <p>Upstream looked the killer up by entity id, which finds a bot when the killer <i>is</i>
     * a bot — that is how bot-versus-bot scores work.
     */
    public void onBotKilledByPlayer(BotKilledByPlayerEvent event) {
        if (registry == null) {
            return;
        }

        Bot killer = registry.byEntityId(event.getPlayer().getId());

        if (killer != null) {
            killer.incrementKills();
        }
    }
}
