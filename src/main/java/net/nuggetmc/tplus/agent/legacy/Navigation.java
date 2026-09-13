package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.motion.MotionVec;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
    private final Mining mining;
    private final BlockScan blockScan;
    private final SurroundingScan surroundingScan;
    private final BotBehaviors behaviors;

    public Navigation(AgentState state, Agent agent, Mining mining, BlockScan blockScan,
                      SurroundingScan surroundingScan, BotBehaviors behaviors) {
        this.state = state;
        this.agent = agent;
        this.mining = mining;
        this.blockScan = blockScan;
        this.surroundingScan = surroundingScan;
        this.behaviors = behaviors;
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

        mining.stopMining(bot);

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

    /**
     * Digs downward when the target is below and out of sight.
     *
     * <p>Ported from {@code checkDown}. Two ways in: the bot is in the same column and more than
     * one block above the target, or it is more than ten blocks above and within ten
     * horizontally. Either way it mines the block it is standing on.
     *
     * <p>The second way is upstream's {@code else}, not a second {@code if}: a bot that satisfies
     * the first test but is standing on nothing gives up here rather than falling through to it.
     *
     * @return true when the bot is now mining, meaning {@code tickBot} must stop here
     */
    public boolean checkDown(Bot bot, Vec3 targetPos, boolean sameColumn) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();

        // Either ray reaching the target means there is no need to dig.
        if (LegacyUtils.checkFreeSpace(level, pos, targetPos)
                || LegacyUtils.checkFreeSpace(level, bot.getEyePosition(), targetPos)) {
            return false;
        }

        List<BlockPos> standing = bot.getStandingOn();

        if (sameColumn && BotMath.floorY(pos) > BotMath.floorY(targetPos) + 1) {
            if (standing.isEmpty()) {
                return false;
            }

            mineBelow(bot, standing.get(0));
            return true;
        }

        // Upstream zeroed the Y of both locations and measured the distance between them, which
        // is this.
        double horizontal = Math.hypot(targetPos.x - pos.x, targetPos.z - pos.z);

        if (BotMath.floorY(pos) > BotMath.floorY(targetPos) + 10 && horizontal < 10) {
            if (standing.isEmpty()) {
                return false;
            }

            mineBelow(bot, standing.get(0));
            return true;
        }

        return false;
    }

    /**
     * Towers upward when the target is above.
     *
     * <p>Ported from {@code checkUp}, about 150 lines. Three cases, keyed on what is in the three
     * blocks at and above the bot's feet:
     *
     * <ul>
     * <li><b>All three clear.</b> Place cobblestone at the bot's feet and jump — the tower.
     * <li><b>Feet and waist clear, head blocked.</b> Mine the block overhead and nudge toward the
     *     block's centre without jumping.
     * <li><b>Stuck in the same column with the waist clear.</b> Mine the block underfoot.
     * </ul>
     *
     * @return true when the bot is now towering or mining
     */
    public boolean checkUp(Bot bot, LivingEntity livingTarget, Vec3 target,
                           boolean withinTargetXZ, boolean sameXZ) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        Vec3 targetPos = livingTarget.position();

        boolean above = LevelRules.aboveGround(level, pos);
        BlockPos ahead = facingBlock(bot);

        // A distant target under open sky is not worth towering toward. The distance is
        // horizontal: upstream zeroed both Y values before measuring.
        if (ahead == null || BlockRules.isBreak(level.getBlockState(ahead))) {
            if (Math.hypot(targetPos.x - pos.x, targetPos.z - pos.z) >= 16 && above) {
                return false;
            }
        }

        if (BotMath.floorY(pos) >= BotMath.floorY(targetPos) - 1) {
            return false;
        }

        BlockPos feet = BlockPos.containing(pos);
        BlockState m0 = level.getBlockState(feet);

        boolean clear0 = BlockRules.isBreak(m0);
        boolean clear1 = BlockRules.isBreak(level.getBlockState(feet.above()));
        boolean clear2 = BlockRules.isBreak(level.getBlockState(feet.above(2)));

        if (clear0 && clear1 && clear2) {
            return tower(bot, livingTarget, target, feet, m0, withinTargetXZ);
        }

        if (clear0 && clear1) {
            bot.look(Direction.UP);
            mining.preBreak(bot, feet.above(2), ScanOffset.ABOVE);

            if (bot.isBotOnGround()) {
                nudgeToCentre(bot, 0);
            }

            return true;
        }

        if (sameXZ && clear1) {
            List<BlockPos> standing = bot.getStandingOn();

            // The Y comparison is against the bot's own block, not the target's — a bot whose
            // feet are inside the block it is standing on, which is what being stuck looks like.
            if (!standing.isEmpty()
                    && standing.get(0).getY() == BotMath.floorY(pos)
                    && !BlockRules.isBreak(level.getBlockState(standing.get(0)))) {
                mineBelow(bot, standing.get(0));
                return true;
            }
        }

        return false;
    }

    /**
     * The tower step: sneak, punch, place at the feet, then jump.
     *
     * <p>{@code feet} is captured before the three-tick delay and used inside it. That is
     * upstream's, and it matters: by the time the block goes down the bot has jumped off the
     * spot, and reading its position again inside the lambda would build the pillar one block
     * too high.
     */
    private boolean tower(Bot bot, LivingEntity livingTarget, Vec3 target, BlockPos feet,
                          BlockState m0, boolean withinTargetXZ) {
        Vec3 pos = bot.position();

        bot.setItem(new ItemStack(Items.COBBLESTONE));
        mining.stopMining(bot);
        bot.look(Direction.DOWN);

        // Upstream's comment: "maybe put this in lower if statement onGround()". The water guard
        // stops a bot in water sealing itself in.
        if (m0.getBlock() != Blocks.WATER) {
            agent.later(3, () -> {
                bot.sneak();
                bot.setItem(new ItemStack(Items.COBBLESTONE));
                bot.punch();
                bot.look(Direction.DOWN);

                agent.later(1, () -> bot.look(Direction.DOWN));

                blockScan.placeBlock(bot, feet);

                if (!state.towerList.containsKey(bot) && withinTargetXZ) {
                    state.towerList.put(bot, bot.position());
                }
            });
        }

        if (!bot.isBotOnGround()) {
            return false;
        }

        if (livingTarget.position().distanceTo(pos) < 16) {
            if (state.noJump.contains(bot)) {
                // Straight up, next tick, and reported as unhandled: a bot that must not jump
                // still has to rise, but tickBot carries on to move() the way upstream's did.
                agent.later(1, () -> bot.setVelocity(new MotionVec(0, 0.5, 0)));
                return false;
            }

            MotionVec vector = MotionVec.of(target.subtract(pos)).normalize();
            bot.stand();

            MotionVec move = bot.getVelocity().add(vector);

            if (move.length() > 1) {
                move.normalize();
            }

            move.multiply(0.1).setY(0.5);

            bot.setVelocity(move);
            return true;
        }

        nudgeToCentre(bot, 0.5);
        return true;
    }

    /** Mines the block underfoot: look down, nudge onto it, and start breaking. */
    private void mineBelow(Bot bot, BlockPos block) {
        bot.look(Direction.DOWN);
        mining.downMine(bot, block);
        mining.preBreak(bot, block, ScanOffset.BELOW);
    }

    /**
     * Pushes the bot toward the centre of its own block, with {@code upward} vertical impulse.
     *
     * <p>Upstream repeated this four times with different Y values. Same arithmetic as
     * {@code Mining.downMine}'s first nudge, and the same unreachable {@code length() > 1} guard.
     */
    private void nudgeToCentre(Bot bot, double upward) {
        Vec3 pos = bot.position();
        Vec3 centre = new Vec3(Math.floor(pos.x) + 0.5, pos.y, Math.floor(pos.z) + 0.5);

        MotionVec vector = MotionVec.of(centre.subtract(pos));

        if (vector.length() > 1) {
            vector.normalize();
        }

        vector.multiply(0.1).setY(upward);
        bot.addVelocity(vector);
    }

    /**
     * Decides whether the bot should move, stay put, or move despite being blocked.
     *
     * <p>Ported from {@code checkSide}. The three return values are upstream's, and
     * {@code tickBot}'s switch reads them directly:
     *
     * <ul>
     * <li><b>1</b> — nothing in the way. Reset the hand and move.
     * <li><b>0</b> — something to the side, above or below. Stay put; the scan has already
     *     started breaking it.
     * <li><b>2</b> — something in the way that is neither. Move anyway.
     * </ul>
     *
     * <p>The early return is the important part: a target within 2.9 blocks with a clear line to
     * the block above it needs no scan at all, so a bot in melee range never starts mining.
     *
     * <p>{@code isSide()} is true for everything except ABOVE, BELOW, AT and AT_D, so the last
     * condition reduces to "anything but AT and AT_D". Upstream wrote it the long way and it
     * stays long: the two spellings stop meaning the same thing the moment a constant is added
     * to {@link ScanOffset}.
     */
    public byte checkSide(Bot bot, LivingEntity target) {
        ServerLevel level = (ServerLevel) bot.level();

        Vec3 a = bot.getEyePosition();
        Vec3 b = target.position().add(0, 1, 0);

        if (bot.position().distanceTo(target.position()) < 2.9
                && LegacyUtils.checkFreeSpace(level, a, b)) {
            behaviors.resetHand(bot, target);
            return 1;
        }

        ScanOffset offset = surroundingScan.checkNearby(bot, target);

        if (offset == null) {
            behaviors.resetHand(bot, target);
            return 1;
        }

        if (offset.isSide() || offset == ScanOffset.BELOW || offset == ScanOffset.ABOVE) {
            return 0;
        }

        return 2;
    }

    /**
     * The block one step in the bot's facing direction, at waist height.
     *
     * <p>Upstream switched on {@code getFacing()} with a {@code default} that produced null.
     * {@code Entity.getDirection()} is derived from yaw and is always horizontal, so the null
     * branch is unreachable — kept because {@code checkUp} reads it.
     */
    private static @Nullable BlockPos facingBlock(Bot bot) {
        Direction dir = bot.getDirection();

        if (dir.getAxis().isVertical()) {
            return null;
        }

        return BlockPos.containing(bot.position()).above().relative(dir);
    }
}
