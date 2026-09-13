package net.nuggetmc.tplus.event;

import net.minecraft.core.BlockPos;
import net.nuggetmc.tplus.bot.Bot;

import java.util.List;

/**
 * A bot is about to take fall damage. Fired from {@code Bot.fallDamageCheck}.
 *
 * <p>{@code standingOn} is what the bot is about to land on, and {@code LegacyAgent.onFallDamage}
 * searches it for somewhere to dump a water bucket — an MLG. Cancelling the event is what
 * "the clutch worked" means.
 *
 * <p>Upstream held Bukkit {@code Block}s and copied the list defensively at the call site. Ours
 * holds positions, because a {@code BlockState} read now could be stale by the time the handler
 * looks at it, and the handler needs the level anyway.
 */
public final class BotFallDamageEvent {

    private final Bot bot;
    private final List<BlockPos> standingOn;
    private boolean cancelled;

    public BotFallDamageEvent(Bot bot, List<BlockPos> standingOn) {
        this.bot = bot;
        this.standingOn = standingOn;
    }

    public Bot getBot() {
        return bot;
    }

    public List<BlockPos> getStandingOn() {
        return standingOn;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
