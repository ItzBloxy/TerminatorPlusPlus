package net.nuggetmc.tplus.bot;

import net.minecraft.server.MinecraftServer;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.util.BotLog;
import net.nuggetmc.tplus.util.TickScheduler;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live bots and drives the per-tick work that is not the entity tick.
 *
 * <p>Spec section 7: each bot's tick is isolated, so one misbehaving bot cannot take
 * down the server tick. A bot that throws on three consecutive ticks is evicted.
 */
public final class BotRegistry {

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * Bot is used as a set element and map key. {@code Entity} overrides equals/hashCode
     * in terms of {@code getId()}, not identity — safe here because entity ids come from
     * a monotonic counter, are never reused, and never change while an entity lives.
     */
    private final Set<Bot> bots = ConcurrentHashMap.newKeySet();
    private final Map<Bot, Integer> failures = new HashMap<>();
    private final TickScheduler scheduler = new TickScheduler();
    private final AgentState state = new AgentState();

    /**
     * Starts as a no-op so nothing has to null-check. Task 12 replaces it with LegacyAgent, and
     * a test can swap in a stub.
     */
    private Agent agent = Agent.noop(this);

    private boolean mobTarget;

    public TickScheduler scheduler() {
        return scheduler;
    }

    /** The shared agent state, so the registry and the agent see the same instance. */
    public AgentState state() {
        return state;
    }

    public Agent agent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent.stopAllTasks();
        this.agent = agent;
    }

    /** Whether hostile mobs may pick bots as a target. Upstream's BotManagerImpl default. */
    public boolean isMobTarget() {
        return mobTarget;
    }

    public void setMobTarget(boolean mobTarget) {
        this.mobTarget = mobTarget;
    }

    /**
     * A snapshot of the live bots, safe to hold across anything.
     *
     * <p>Prefer {@link #botsView()} on a per-tick path: this copies.
     */
    public Collection<Bot> bots() {
        return Set.copyOf(bots);
    }

    /**
     * A live, read-only view of the bots.
     *
     * <p>Upstream's {@code fetch()} returned the backing set itself, and the agent's three
     * bot-targeting goals call it once per bot per tick — so copying here is O(bots squared)
     * allocations every tick, in a plugin whose whole point is spawning hundreds of them.
     *
     * <p>Iterating this without copying is safe: the backing set is a
     * {@code ConcurrentHashMap} key set, whose iterator is weakly consistent and never throws
     * {@code ConcurrentModificationException}. A caller that removes bots while looping still
     * wants {@link #bots()} or an explicit {@code List.copyOf}, which is what {@link #tick()}
     * and {@link #reset()} do.
     */
    public Collection<Bot> botsView() {
        return Collections.unmodifiableSet(bots);
    }

    public int size() {
        return bots.size();
    }

    /** Registers the bot and tells it which registry owns it. */
    public void add(Bot bot) {
        bot.setRegistry(this);
        bots.add(bot);
    }

    public void remove(Bot bot) {
        bots.remove(bot);
        failures.remove(bot);

        // Cancel before forgetting. The swing animation is a repeating task and miningAnim holds
        // the only handle to it, so dropping the entry first leaves it punching a removed bot
        // every four ticks until the agent is disabled. Upstream leaked the same task but had no
        // forget() to lose the handle in.
        Integer anim = state.miningAnim.get(bot);

        if (anim != null) {
            agent.cancel(anim);
        }

        state.forget(bot);

        // Agent-held per-bot state, for collaborators that deliberately do not keep theirs in
        // AgentState. Archery is the first; the backlog asks for that narrowing, and new state is
        // the easy case, since nothing outside the owner reads it.
        agent.forgetBot(bot);
    }

    /** Ported from {@code BotManagerImpl.getBot(int)}. */
    public Bot byEntityId(int entityId) {
        for (Bot bot : bots) {
            if (bot.getId() == entityId) {
                return bot;
            }
        }
        return null;
    }

    public Bot byName(String name) {
        for (Bot bot : bots) {
            if (bot.getGameProfile().name().equalsIgnoreCase(name)) {
                return bot;
            }
        }
        return null;
    }

    /** Called once per server tick. */
    public void tick() {
        scheduler.tick();

        if (agent.isEnabled()) {
            // Per-tick setup, outside the per-bot loop: if this throws there is no single bot
            // to blame, so it is deliberately not inside the isolation below.
            agent.tick();
        }

        for (Bot bot : List.copyOf(bots)) {
            if (!bot.isAlive() && bot.isRemoved()) {
                remove(bot);
                continue;
            }

            try {
                tickBot(bot);
            } catch (Throwable t) {
                noteTickFailure(bot, t);
            }
        }
    }

    /**
     * Records a failed tick and evicts the bot once it has failed
     * {@value #MAX_CONSECUTIVE_FAILURES} times in a row.
     *
     * <p>Called from two places: {@link #tick()} for the agent hook, and
     * {@code Bot.tick()} for the entity tick. The entity tick is the one that can crash
     * the server, so it must route here too.
     */
    public void noteTickFailure(Bot bot, Throwable t) {
        int count = failures.merge(bot, 1, Integer::sum);
        TerminatorPlus.LOGGER.error("Bot '{}' failed its tick ({}/{})",
                bot.getGameProfile().name(), count, MAX_CONSECUTIVE_FAILURES, t);

        if (count >= MAX_CONSECUTIVE_FAILURES) {
            // The one line Plan B adds rather than translates: an operator watching a bot die
            // repeatedly could not previously see why without reading the server log.
            BotLog.debug(bot.level().getServer(),
                    "Evicting bot '" + bot.getGameProfile().name() + "' after " + count
                            + " consecutive failures");
            safeRemove(bot);
        }
    }

    /** Clears the consecutive-failure counter after a healthy tick. */
    public void clearTickFailures(Bot bot) {
        failures.remove(bot);
    }

    /** Agent hook, isolated per bot so one failure cannot stop the others. */
    private void tickBot(Bot bot) {
        if (agent.isEnabled()) {
            agent.tickBot(bot);
        }
    }

    private void safeRemove(Bot bot) {
        remove(bot);
        try {
            bot.removeBot();
        } catch (Throwable t) {
            TerminatorPlus.LOGGER.error("Failed to clean up bot '{}'", bot.getGameProfile().name(), t);
        }
    }

    /** Removes every bot. Called on server shutdown. */
    public void reset() {
        // Before removing anything: stopAllTasks is what cancels in-flight swing animations
        // and shield timers, and LegacyAgent overrides it to clear the crack overlays too.
        agent.stopAllTasks();

        for (Bot bot : List.copyOf(bots)) {
            safeRemove(bot);
        }
        bots.clear();
        failures.clear();
        scheduler.cancelAll();
    }

    /**
     * Runs {@code action} on the server thread, whether or not the caller is on it.
     *
     * <p>Uses {@code executeIfPossible} rather than {@code execute}: an async skin lookup
     * can complete after the server has begun shutting down, and that variant drops the
     * task instead of failing.
     */
    public static void onServerThread(MinecraftServer server, Runnable action) {
        if (server.isSameThread()) {
            action.run();
        } else {
            server.executeIfPossible(action);
        }
    }
}
