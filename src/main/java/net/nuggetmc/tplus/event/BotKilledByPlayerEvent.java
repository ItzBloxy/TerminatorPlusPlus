package net.nuggetmc.tplus.event;

import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.bot.Bot;

/**
 * A player just killed a bot.
 *
 * <p>Upstream's comment on this class is worth keeping: "eventually also call this event for
 * deaths from other damage causes within combat time (like hitting the ground too hard)". It
 * does not do that, and neither does this.
 *
 * <p>{@code Agent.onBotKilledByPlayer} uses it to credit the kill — to the killer's own bot, if
 * the killer is driving one.
 */
public final class BotKilledByPlayerEvent {

    private final Bot bot;
    private final ServerPlayer player;

    public BotKilledByPlayerEvent(Bot bot, ServerPlayer player) {
        this.bot = bot;
        this.player = player;
    }

    public Bot getBot() {
        return bot;
    }

    public ServerPlayer getPlayer() {
        return player;
    }
}
