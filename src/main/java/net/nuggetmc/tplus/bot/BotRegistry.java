package net.nuggetmc.tplus.bot;

import net.minecraft.server.MinecraftServer;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.util.TickScheduler;

import java.util.Collection;
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

    private final Set<Bot> bots = ConcurrentHashMap.newKeySet();
    private final Map<Bot, Integer> failures = new HashMap<>();
    private final TickScheduler scheduler = new TickScheduler();

    public TickScheduler scheduler() {
        return scheduler;
    }

    public Collection<Bot> bots() {
        return Set.copyOf(bots);
    }

    public int size() {
        return bots.size();
    }

    public void add(Bot bot) {
        bots.add(bot);
    }

    public void remove(Bot bot) {
        bots.remove(bot);
        failures.remove(bot);
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
            TerminatorPlus.LOGGER.error("Evicting bot '{}' after {} consecutive failures",
                    bot.getGameProfile().name(), count);
            safeRemove(bot);
        }
    }

    /** Clears the consecutive-failure counter after a healthy tick. */
    public void clearTickFailures(Bot bot) {
        failures.remove(bot);
    }

    /**
     * Agent hook. Plan A has no agent, so a bot only does what its entity tick does.
     * Plan B dispatches to LegacyAgent here.
     */
    private void tickBot(Bot bot) {
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
        for (Bot bot : List.copyOf(bots)) {
            safeRemove(bot);
        }
        bots.clear();
        failures.clear();
        scheduler.cancelAll();
    }

    /** Runs {@code action} on the server thread, whether or not the caller is on it. */
    public static void onServerThread(MinecraftServer server, Runnable action) {
        if (server.isSameThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }
}
