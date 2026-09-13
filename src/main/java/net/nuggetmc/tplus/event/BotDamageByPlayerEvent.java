package net.nuggetmc.tplus.event;

import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.bot.Bot;

/**
 * A player is about to damage a bot. Fired from {@code Bot.hurtServer} before the hit lands.
 *
 * <p>Not a bus event: upstream passed this straight to {@code agent.onPlayerDamage(event)} and
 * never went near the Bukkit event bus (plan correction 2). {@code LegacyAgent} uses it to
 * cancel hits that a raised shield should have blocked.
 */
public final class BotDamageByPlayerEvent {

    private final Bot bot;
    private final ServerPlayer player;
    private float damage;
    private boolean cancelled;

    public BotDamageByPlayerEvent(Bot bot, ServerPlayer player, float damage) {
        this.bot = bot;
        this.player = player;
        this.damage = damage;
    }

    public Bot getBot() {
        return bot;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public float getDamage() {
        return damage;
    }

    public void setDamage(float damage) {
        this.damage = damage;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
