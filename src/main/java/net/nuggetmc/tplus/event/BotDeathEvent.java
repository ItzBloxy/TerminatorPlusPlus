package net.nuggetmc.tplus.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.nuggetmc.tplus.bot.Bot;

import java.util.Collection;

/**
 * A bot died, and its drops have not been spawned yet.
 *
 * <p>Upstream extended Bukkit's {@code EntityDeathEvent} to get at the staged drop list. Vanilla
 * has no staging step, so {@code TerminatorPlus} bridges NeoForge's {@code LivingDropsEvent} into
 * this and hands over that event's live collection. {@code LegacyAgent.onBotDeath} clears it when
 * drops are disabled, which is the only thing any handler ever did with it.
 *
 * <p>The collection is mutable on purpose. Clearing it is the API.
 */
public final class BotDeathEvent {

    private final Bot bot;
    private final DamageSource source;
    private final Collection<ItemEntity> drops;

    public BotDeathEvent(Bot bot, DamageSource source, Collection<ItemEntity> drops) {
        this.bot = bot;
        this.source = source;
        this.drops = drops;
    }

    public Bot getBot() {
        return bot;
    }

    public DamageSource getSource() {
        return source;
    }

    public Collection<ItemEntity> getDrops() {
        return drops;
    }
}
