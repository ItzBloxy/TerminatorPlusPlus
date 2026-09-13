package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.motion.MotionVec;

/**
 * How a bot gets from where it is to where its target is.
 *
 * <p>Ported from {@code LegacyAgent.move} and {@code swim}. {@code checkSide}, {@code checkUp}
 * and {@code checkDown} join them in tasks 19 and 21 — they belong here by spec §4.3, and they
 * need the mining code that does not exist yet.
 */
public final class Navigation {

    private final AgentState state;
    private final Agent agent;

    public Navigation(AgentState state, Agent agent) {
        this.state = state;
        this.agent = agent;
    }

    /**
     * Hops the bot toward {@code target}.
     *
     * <p>Ported from {@code move}. Bots do not walk: every step is a jump with a horizontal
     * impulse, which is why they look the way they do and why {@code isBotOnGround} gates the
     * whole method.
     *
     * <p>The neural-network branch (upstream lines 243-297) is omitted — see plan correction 4.
     * It rotated the impulse by a learned left/right bias and chose between a jump and a
     * walk-then-jump; all of it is unreachable with the AI deferred.
     */
    public void move(Bot bot, LivingEntity livingTarget, Vec3 pos, Vec3 target) {
        MotionVec vel = MotionVec.of(target.subtract(pos)).normalize();

        if (bot.tickDelay(5)) {
            bot.faceLocation(livingTarget.position());
        }

        // Upstream calls this twice — once here, once implicitly through jump's groundTicks
        // check. The comment on the original reads "calling this a second time later on".
        if (!bot.isBotOnGround()) {
            return;
        }

        bot.stand();
        bot.setItem(null);

        vel.add(bot.getVelocity());

        // A deliberate deviation, and the only one in this method. Upstream wrapped the add
        // above in `try { ... } catch (IllegalArgumentException)` and cleaned the vector in the
        // catch — but Bukkit's Vector.add cannot throw that, so the guard never ran. MotionVec
        // propagates NaN silently (spec §2.5), the NaN reaches Entity.move through jump(), and
        // a poisoned position is not something the per-bot tick isolation can undo. The guard
        // therefore runs unconditionally. Reachable when the target is exactly the bot's own
        // position, which `offsets` normally prevents.
        if (BotMath.isNotFinite(vel)) {
            BotMath.clean(vel);
        }

        if (vel.length() > 1) {
            vel.normalize();
        }

        double distance = pos.distanceTo(target);

        if (distance <= 5) {
            vel.multiply(0.3);
        } else {
            vel.multiply(0.4);
        }

        if (state.slow.contains(bot)) {
            // Note the order: setY(0) then multiply, so Y stays 0 rather than being halved.
            vel.setY(0).multiply(0.5);
        } else {
            vel.setY(0.4);
        }

        vel.setY(vel.getY() - Math.random() * 0.05);

        bot.jump(vel);
    }

    /**
     * Pushes the bot through water toward {@code target}.
     *
     * <p>Ported from {@code swim}. Much gentler than {@link #move}: 0.05 of impulse rather than
     * a jump, because water already carries the bot.
     *
     * @param anim true when there is water below the bot too, in which case it also takes the
     *             swimming pose. With no water below, the impulse is flattened and scaled to 0.7.
     */
    public void swim(Bot bot, Vec3 target, LivingEntity livingTarget, boolean anim) {
        // Upstream opened with setSneaking(false) and nothing else. Calling bot.stand() here
        // would be wrong: stand() also clears the swim flag, so the non-anim branch below would
        // silently stop a swimming bot from swimming. setShiftKeyDown is vanilla's public
        // equivalent of Bukkit's setSneaking and touches only the one flag.
        bot.setShiftKeyDown(false);

        Vec3 at = bot.position();
        MotionVec vector = MotionVec.of(target.subtract(at));

        if (BotMath.floorY(at) < BotMath.floorY(livingTarget.position())) {
            vector.setY(0);
        }

        vector.normalize().multiply(0.05);
        vector.setY(vector.getY() * 1.2);

        cancelMiningAnim(bot);

        if (anim) {
            bot.swim();
        } else {
            vector.setY(0);
            vector.multiply(0.7);
        }

        // Same guard as move(): a target exactly on the bot's own position normalises to NaN,
        // and addVelocity would carry it into the physics vector.
        if (BotMath.isNotFinite(vector)) {
            BotMath.clean(vector);
        }

        bot.faceLocation(livingTarget.position());
        bot.addVelocity(vector);
    }

    /** Cancels a running swing animation, if any. Upstream inlined this in five places. */
    void cancelMiningAnim(Bot bot) {
        Integer task = state.miningAnim.remove(bot);

        if (task != null) {
            agent.cancel(task);
        }
    }
}
