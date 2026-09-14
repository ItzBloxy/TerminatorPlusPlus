package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.ranged.BowBallistics;
import net.nuggetmc.tplus.bot.ranged.RangedContext;
import net.nuggetmc.tplus.bot.ranged.RangedDecision;
import net.nuggetmc.tplus.bot.ranged.RangedMode;
import net.nuggetmc.tplus.bot.ranged.RangedSettings;
import net.nuggetmc.tplus.util.PlayerUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The bow. Samples the world into a {@link RangedContext}, asks {@link RangedDecision}, and runs
 * a per-bot draw state machine when the answer is RANGED.
 *
 * <p>Upstream had no ranged combat, so none of this is a translation. It sits beside
 * {@code Navigation} and {@code Mining} and takes the same injected {@code AgentState}.
 *
 * <p><b>The draw is ticked here rather than scheduled.</b> The shield schedules its release with
 * {@code scheduler.runLater}; a bow cannot, because a scheduled release cannot re-aim and a Ghast
 * drifts. Ticking also means a bot that dies or is removed simply stops, with no task to cancel.
 *
 * <p><b>Firing bypasses {@code BowItem.releaseUsing} entirely.</b> That method's first step reads
 * {@code useItemRemaining}, which never decrements for a bot — {@code Bot.isBotBlocking}
 * documents the whole chain — but it reads it only to recover how long the draw has been held,
 * which this class already knows because it started the draw. The arrow is spawned directly, the
 * way {@code Projectile.spawnProjectileFromRotation} does. <b>This does not deliver the generic
 * use-tick</b>: food, potions and the shield are exactly as blocked as before. Deviation 41.
 */
public final class Archery {

    /** {@code BowItem.MAX_DRAW_DURATION}: a full draw, so power is 1.0 and the arrow is critical. */
    public static final int DRAW_TICKS = 20;

    /** Ticks after a release before the next draw. 20 + 10 is about a player's sustained rate. */
    public static final int RELEASE_COOLDOWN = 10;

    /** How often the line-of-sight check is re-evaluated. Matches melee's {@code tickDelay(3)}. */
    public static final int SIGHT_PERIOD = 3;

    /** {@code BowItem.releaseUsing} passes {@code pow * 3.0F}; a full draw makes pow 1.0. */
    private static final float LAUNCH_POWER = (float) BowBallistics.FULL_DRAW_POWER;

    private enum Phase {
        IDLE,
        DRAWING,
        COOLDOWN
    }

    /** Everything this class remembers about one bot. Not in {@code AgentState} — deviation 43. */
    private static final class State {
        RangedMode mode = RangedMode.MELEE;
        int ticksInMode;

        Phase phase = Phase.IDLE;
        int phaseTicks;

        @Nullable UUID targetId;
        int aloftTicks;

        boolean sight;
        int sightAge = Integer.MAX_VALUE;

        @Nullable RangedDecision lastDecision;
    }

    private final AgentState state;
    private final Agent agent;
    private final Mining mining;

    private final Map<Bot, State> bots = new HashMap<>();

    private RangedSettings settings = RangedSettings.DEFAULTS;

    public Archery(AgentState state, Agent agent, Mining mining) {
        this.state = state;
        this.agent = agent;
        this.mining = mining;
    }

    public RangedSettings settings() {
        return settings;
    }

    public void setSettings(RangedSettings value) {
        this.settings = value;
    }

    public @Nullable RangedDecision lastDecision(Bot bot) {
        State s = bots.get(bot);
        return s == null ? null : s.lastDecision;
    }

    public boolean isDrawing(Bot bot) {
        State s = bots.get(bot);
        return s != null && s.phase == Phase.DRAWING;
    }

    /**
     * One tick of ranged behaviour.
     *
     * @return true when the bot is holding position to shoot, which {@code tickBot} reads as
     *         "handled, stop here" and is the whole of the hold-position decision
     */
    public boolean tick(Bot bot, Entity target) {
        State s = bots.computeIfAbsent(bot, b -> new State());

        // A bot mid-boat-crossing owns its own hand. resetHand has the same early return, whose
        // comment reads "leaves the boat in its hand"; without this a bot swaps the boat for a
        // bow halfway across a lava lake.
        if (state.boatCooldown.contains(bot)) {
            return false;
        }

        trackAloft(s, target);

        RangedContext ctx = sample(bot, target, s);
        RangedDecision decision = RangedDecision.decide(ctx, settings);
        s.lastDecision = decision;

        if (decision.mode() != s.mode) {
            s.mode = decision.mode();
            s.ticksInMode = 0;
        } else {
            s.ticksInMode++;
        }

        if (!decision.isRanged()) {
            if (s.phase != Phase.IDLE) {
                stopDraw(bot, s);
            }

            return false;
        }

        // BOT_STUCK fires precisely when a bot is mining and getting nowhere, so a mining bot
        // entering RANGED is the common path rather than an edge case. Navigation.tower and
        // resetHand both call this before taking the hand; so does this.
        mining.stopMining(bot);

        advance(bot, target, s);
        return true;
    }

    /** Drops any draw and gives the hand back. Safe on a bot this class has never seen. */
    public void reset(Bot bot) {
        State s = bots.get(bot);

        if (s == null) {
            return;
        }

        if (s.phase != Phase.IDLE) {
            stopDraw(bot, s);
        }

        s.mode = RangedMode.MELEE;
        s.ticksInMode = 0;
        s.aloftTicks = 0;
        s.targetId = null;
        s.sightAge = Integer.MAX_VALUE;
    }

    /** Forgets a bot entirely. Called from {@code BotRegistry.remove}. */
    public void forget(Bot bot) {
        bots.remove(bot);
    }

    /** Forgets every bot. Called from {@code LegacyAgent.stopAllTasks}. */
    public void clear() {
        bots.clear();
    }

    // ---- the state machine ------------------------------------------------

    private void advance(Bot bot, Entity target, State s) {
        switch (s.phase) {
            case IDLE -> startDraw(bot, s);

            case DRAWING -> {
                s.phaseTicks++;
                aim(bot, target);

                if (s.phaseTicks >= DRAW_TICKS) {
                    release(bot, target, s);
                }
            }

            case COOLDOWN -> {
                s.phaseTicks++;
                aim(bot, target);

                if (s.phaseTicks >= RELEASE_COOLDOWN) {
                    startDraw(bot, s);
                }
            }
        }
    }

    private void startDraw(Bot bot, State s) {
        s.phase = Phase.DRAWING;
        s.phaseTicks = 0;

        bot.setItem(bot.bowStack().copy());

        // The client renders another player's bow pull from the living-entity-flags byte and
        // counts the draw ticks itself, so this pair is the whole animation -- the same pair the
        // shield path sends. The server-side useItemRemaining counter stays frozen at zero and
        // nothing here reads it.
        bot.startUsingItem(InteractionHand.MAIN_HAND);
        bot.broadcastEntityData();
    }

    private void stopDraw(Bot bot, State s) {
        s.phase = Phase.IDLE;
        s.phaseTicks = 0;

        bot.stopUsingItem();
        bot.broadcastEntityData();

        // null means "restore the default item", not "empty".
        bot.setItem(null);
    }

    private void release(Bot bot, Entity target, State s) {
        ServerLevel level = (ServerLevel) bot.level();

        // The firing line gates the shot, not the mode: a squadmate wandering through is
        // transient, and flipping mode over it would flicker the bot in and out of navigation.
        // So a blocked line holds position, holds fire, and looses when it clears.
        if (canFire(bot, target, level)) {
            shoot(bot, level);
        }

        s.phase = Phase.COOLDOWN;
        s.phaseTicks = 0;

        bot.stopUsingItem();
        bot.broadcastEntityData();
    }

    private boolean canFire(Bot bot, Entity target, ServerLevel level) {
        Vec3 eye = bot.getEyePosition();

        if (!RangedSight.canSee(level, eye, target)) {
            return false;
        }

        // Friendly fire is left on deliberately -- bot arrows are owned by a Player and gated by
        // canHarmPlayer, which says yes by default. This only declines shots a squadmate would
        // physically swallow, so a draw is not wasted on one.
        Vec3 aimPoint = aimPoint(target);

        for (Bot other : agent.registryBots()) {
            // The target is excluded, and forgetting that is not a hypothetical: bots are
            // ServerPlayers, so a bot hunting another bot -- which is what TOWER_QUOTA and the
            // NEAREST_BOT goals produce -- put its own target on the firing line and refused
            // every shot. `a_bot_draws_and_fires_at_a_flying_target` caught it on the first run.
            if (other == bot || other == target || !other.isBotAlive()) {
                continue;
            }

            if (segmentHitsBot(eye, aimPoint, other)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Whether {@code other}'s hitbox lies on the segment from {@code from} to {@code to}.
     *
     * <p>An approximation, and knowingly so: the arrow arcs and this is a straight line. It is a
     * shot filter rather than a correctness requirement, and the crowding gate is what actually
     * keeps archers out of each other's backs.
     */
    private static boolean segmentHitsBot(Vec3 from, Vec3 to, Bot other) {
        return other.getBoundingBox().inflate(0.3).clip(from, to).isPresent();
    }

    private void shoot(Bot bot, ServerLevel level) {
        ItemStack bow = bot.bowStack();

        // A fresh stack every shot: the bot's inventory is never read and useAmmo is never
        // called. Consistent with the cobblestone it towers with and the water buckets it
        // clutches with, and with tools that never lose durability. Deviation 40.
        Arrow arrow = new Arrow(level, bot, new ItemStack(Items.ARROW), bow);

        // Otherwise twenty archers carpet the ground with collectables, and each one is a
        // plausible `enemytarget generic minecraft:arrow` result.
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;

        // A full draw is a critical arrow, matching the `pow == 1.0F` argument
        // BowItem.releaseUsing passes.
        arrow.setCritArrow(true);

        arrow.shootFromRotation(bot, bot.getXRot(), bot.getYRot(), 0f, LAUNCH_POWER, 1.0f);
        level.addFreshEntity(arrow);

        bot.punch();

        level.playSound(null, bot.blockPosition(), SoundEvents.ARROW_SHOOT,
                SoundSource.PLAYERS, 1f, 1f);
    }

    // ---- aiming -----------------------------------------------------------

    private void aim(Bot bot, Entity target) {
        // The arrow is born at getEyeY() - 0.1F, not at the eye. A tenth of a block is nothing
        // in angle terms at 20 blocks, but BowBallistics is pure and solving from the wrong
        // origin biases every shot in the same direction.
        Vec3 origin = bot.getEyePosition().subtract(0, BowBallistics.LAUNCH_EYE_OFFSET, 0);

        BowBallistics.Aim aim = BowBallistics.solveWithLead(
                origin, aimPoint(target), target.getDeltaMovement(), BowBallistics.FULL_DRAW_POWER);

        if (aim == null) {
            // Out of reach at every angle. Keep facing the target so the bot does not look
            // broken, and let the next tick try again -- the target may come closer.
            bot.faceLocation(target.position());
            return;
        }

        bot.lookAt(aim.yaw(), aim.pitch());
    }

    /**
     * Where to point, which is not always the target's eyes.
     *
     * <p>The Ender Dragon is the exception. {@code Level.getEntities} merges {@code dragonParts()}
     * into every query, so an arrow hits the dragon with no special handling — but it hits
     * whichever {@code EnderDragonPart} is geometrically in the way, and {@code EnderDragon.hurt}
     * opens with {@code if (part != this.head) damage = damage / 4 + min(damage, 1)}. Deviation 37
     * fixed that for melee by redirecting the recipient; a projectile has no recipient to
     * redirect, so the fix moves into the aim point. It does not guarantee a head hit — a wing
     * can still intercept — so it is an improvement in expectation.
     */
    private static Vec3 aimPoint(Entity target) {
        return target instanceof EnderDragon dragon
                ? dragon.head.position()
                : target.getEyePosition();
    }

    // ---- sampling ---------------------------------------------------------

    private void trackAloft(State s, Entity target) {
        UUID id = target.getUUID();

        // Targeting.locateTarget runs every tick and may return a different entity than it did
        // last tick. Carrying the count across a switch would let a grounded target inherit a
        // Phantom's 40 ticks and flip the bot to RANGED against something standing on the floor.
        if (!id.equals(s.targetId)) {
            s.targetId = id;
            s.aloftTicks = 0;
        }

        s.aloftTicks = target.onGround() ? 0 : s.aloftTicks + 1;
    }

    private RangedContext sample(Bot bot, Entity target, State s) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        Vec3 targetPos = target.position();

        boolean invincible = target instanceof ServerPlayer player
                && PlayerUtils.isInvincible(player.gameMode());

        double distance = pos.distanceTo(targetPos);

        return new RangedContext(
                bot.hasBow(),
                invincible,
                distance,
                lineOfSight(bot, target, level, s),
                bot.isBotOnGround(),
                countNearbyBots(bot, pos),
                s.aloftTicks,
                countTowerersNear(targetPos),
                Boolean.TRUE.equals(state.btCheck.get(bot)),
                s.mode,
                s.ticksInMode);
    }

    /** Re-evaluated every {@link #SIGHT_PERIOD} ticks and cached between; see {@link RangedSight}. */
    private boolean lineOfSight(Bot bot, Entity target, ServerLevel level, State s) {
        if (s.sightAge < SIGHT_PERIOD) {
            s.sightAge++;
            return s.sight;
        }

        s.sight = RangedSight.canSee(level, bot.getEyePosition(), target);
        s.sightAge = 1;
        return s.sight;
    }

    private int countNearbyBots(Bot bot, Vec3 pos) {
        double radiusSqr = settings.crowdRadius() * settings.crowdRadius();
        int count = 0;

        for (Bot other : agent.registryBots()) {
            if (other != bot && other.isBotAlive() && other.position().distanceToSqr(pos) <= radiusSqr) {
                count++;
            }
        }

        return count;
    }

    /** {@code state.towerList} is already "which bots are towering", so this is a filter. */
    private int countTowerersNear(Vec3 targetPos) {
        double radiusSqr = settings.towerQuotaRadius() * settings.towerQuotaRadius();
        int count = 0;

        for (Bot towerer : state.towerList.keySet()) {
            if (towerer.isBotAlive() && towerer.position().distanceToSqr(targetPos) <= radiusSqr) {
                count++;
            }
        }

        return count;
    }
}
