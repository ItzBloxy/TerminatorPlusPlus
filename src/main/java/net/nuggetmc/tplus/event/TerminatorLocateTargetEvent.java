package net.nuggetmc.tplus.event;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.nuggetmc.tplus.bot.Bot;
import org.jetbrains.annotations.Nullable;

/**
 * A bot has chosen a target, and anything on the bus may change or veto it.
 *
 * <p>The only one of the five that really was an event upstream: it went through
 * {@code Bukkit.getPluginManager().callEvent}, so third-party plugins could retarget bots. That
 * extension point is preserved here on {@code NeoForge.EVENT_BUS}, which is the nearest thing a
 * server-side mod has to it.
 *
 * <p>Cancelling means "this bot has no target this tick" — {@code Targeting.locateTarget} returns
 * null — which is different from setting the target to null, because a handler that cancels stops
 * later handlers from seeing it.
 *
 * <p>The target may legitimately be null on entry: {@code locateTarget} posts the event even when
 * it found nothing, so a handler can supply a target the goal would never have picked.
 *
 * <p>Upstream's accessor was {@code getTerminator()}. Renamed to {@code getBot()} to match every
 * other event here and the concrete type the v1 agent works against (spec §4.4).
 *
 * <p>{@code ICancellableEvent} supplies {@code isCanceled}/{@code setCanceled} as interface
 * defaults — note the American spelling, which differs from the four plain event classes.
 */
public final class TerminatorLocateTargetEvent extends Event implements ICancellableEvent {

    private final Bot bot;
    private @Nullable LivingEntity target;

    public TerminatorLocateTargetEvent(Bot bot, @Nullable LivingEntity target) {
        this.bot = bot;
        this.target = target;
    }

    public Bot getBot() {
        return bot;
    }

    public @Nullable LivingEntity getTarget() {
        return target;
    }

    public void setTarget(@Nullable LivingEntity target) {
        this.target = target;
    }
}
