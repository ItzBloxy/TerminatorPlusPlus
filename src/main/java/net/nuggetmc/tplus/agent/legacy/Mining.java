package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.EquipmentTier;
import net.nuggetmc.tplus.motion.MotionVec;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Breaking blocks: tool choice, the swing animation, the crack overlay, and the break itself.
 *
 * <p>Ported from {@code LegacyAgent.preBreak}, {@code blockBreakEffect}, {@code downMine},
 * {@code stopMining} and {@code placeWaterDown}.
 *
 * <p>Two independent repeating tasks per mined block, which is upstream's design: a swing
 * animation every 4 ticks keyed on the <b>bot</b>, and a progress counter every 2 ticks keyed on
 * the <b>block</b>. They start together and stop for different reasons — the animation when the
 * bot stops mining, the progress when the target block changes.
 */
public final class Mining {

    /** Ticks between runs of the break task. Upstream's, unchanged. */
    private static final int BREAK_PERIOD = 2;

    /**
     * Break progress one crack stage costs.
     *
     * <p>Defined as <b>iron's progress in one run</b>: iron's mining speed is 6.0 and the task
     * runs every {@value #BREAK_PERIOD} ticks. An iron bot therefore advances exactly one stage
     * per run and breaks a block in twenty ticks, which is upstream's flat behaviour reproduced
     * by construction rather than by coincidence. Every other tier is faster or slower than that
     * anchor: wood 60 ticks, stone 30, copper 24, diamond 15, netherite 14, gold 10.
     */
    private static final int STAGE_COST = 12;

    /** Crack stages a block goes through. A protocol constant: the packet carries 0..9. */
    private static final int STAGES = 10;

    /** Progress that breaks a block. */
    static final int BREAK_COST = STAGE_COST * STAGES;

    /** Pitch while breaking a block one level below eye height. Upstream's magic number. */
    static final float PITCH_DOWN = 69f;

    /** Pitch while breaking a block above. */
    static final float PITCH_UP = -53f;

    /**
     * Blocks upstream refused to break, checked at the last moment before advancing a stage.
     *
     * <p>Upstream listed {@code STRUCTURE_BLOCK} twice. Deduplicated by the set, no behaviour
     * change.
     */
    private static final Set<Block> UNBREAKABLE = Set.of(
            Blocks.BARRIER, Blocks.BEDROCK,
            Blocks.END_PORTAL_FRAME, Blocks.STRUCTURE_BLOCK,
            Blocks.COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK);

    private final AgentState state;
    private final Agent agent;

    public Mining(AgentState state, Agent agent) {
        this.state = state;
        this.agent = agent;
    }

    /**
     * Starts the bot mining the block at {@code pos}.
     *
     * <p>Ported from {@code preBreak}. Picks the fastest of the three tools, aims the bot
     * according to {@code offset}, starts the swing animation if one is not already running, and
     * hands off to {@link #blockBreakEffect}.
     *
     * <p>The aiming is the part that looks arbitrary and is not: a block at foot level needs a
     * steep downward pitch to be in reach, and {@code btCheck} is forced true five ticks later so
     * that {@code checkUp} and {@code checkSide} unlock while the bot is committed to this block.
     */
    public void preBreak(Bot bot, BlockPos pos, ScanOffset offset) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockState target = level.getBlockState(pos);

        bot.setItem(optimalTool(bot.getToolTier(), target));

        if (offset.isSideDown() || offset.isSideDown2()) {
            bot.setBotPitch(PITCH_DOWN);

            // Upstream forces the same-column flag on five ticks in. It is not a measurement —
            // it is a way of telling the rest of tickBot "this bot is committed, let checkUp
            // and checkSide run". Faithfully odd.
            agent.later(5, () -> state.btCheck.put(bot, true));
        } else if (offset.isSideUp()) {
            bot.setBotPitch(PITCH_UP);
        } else if (offset == ScanOffset.AT_D || offset == ScanOffset.AT) {
            // Upstream: block.getLocation().add(0.5, -1, 0.5). A Bukkit block Location is its
            // LOWER CORNER, so that is the centre of the block's footprint one whole block
            // down -- which points the bot's head at the block's lower face rather than
            // through it. atCenterOf would add half a block of Y that upstream did not.
            bot.faceLocation(new Vec3(pos.getX() + 0.5, pos.getY() - 1, pos.getZ() + 0.5));
        }

        if (!state.miningAnim.containsKey(bot)) {
            int id = agent.repeating(4, bot::punch);
            state.miningAnim.put(bot, id);
        }

        blockBreakEffect(bot, pos, new ScanOffset.Wrapper(offset));
    }

    /**
     * Starts the progress counter that eventually breaks the block at {@code pos}.
     *
     * <p>Ported from {@code blockBreakEffect}. One task per block, ticking every
     * {@value #BREAK_PERIOD} ticks through ten crack stages.
     *
     * <p><b>Divergence:</b> upstream advanced one fixed stage per run, so every block took twenty
     * ticks whatever the bot held and whatever it was breaking. Progress here is the held tool's
     * destroy speed against the block, so a bot's tool tier decides how fast it tunnels — see
     * {@link #STAGE_COST}, which is set so that iron still takes exactly twenty. Block
     * <i>hardness</i> is still ignored, as upstream ignored it.
     *
     * <p>Three things make it more than a counter:
     *
     * <ul>
     * <li>It re-derives its target from the bot's <em>current</em> position each tick, through
     *     {@code wrapper}'s offset, and cancels if that no longer matches the block it started
     *     on. That is how mining stops when the bot moves.
     * <li>If the bot is suspended over lava it re-aims one rung up or down the {@link ScanOffset}
     *     ladder and keeps going, mutating the wrapper so the change persists. Upstream's
     *     comment: "Fix boat clutching while breaking block. As a side effect, the bot is able to
     *     break multiple blocks at once while over lava."
     * <li>Seven block types are hard-refused at the last moment — bedrock, barrier, the three
     *     command blocks, the end portal frame and the structure block. It returns without
     *     advancing, so the task spins forever on them rather than stopping. Faithfully wasteful.
     * </ul>
     */
    void blockBreakEffect(Bot bot, BlockPos pos, ScanOffset.Wrapper wrapper) {
        ServerLevel level = (ServerLevel) bot.level();

        if (BlockRules.isNoCrack(level.getBlockState(pos))) {
            return;
        }

        AgentState.BlockRef ref = new AgentState.BlockRef(level.dimension(), pos);

        if (state.crackList.containsKey(ref)) {
            return;
        }

        state.crackList.put(ref, (short) agent.random().nextInt(2000));

        // A one-element array because the task needs its own id to look up its progress, and the
        // id only exists once repeating() has returned. Upstream had the same problem and solved
        // it by keying `mining` on the BukkitRunnable, which was `this` inside the anonymous
        // class. TickScheduler never runs a task inline, so the id is always set before the
        // first run.
        int[] taskId = new int[1];

        taskId[0] = agent.repeating(BREAK_PERIOD, () -> {
            short progress = state.mining.getOrDefault(taskId[0], (short) 0);

            BlockPos current = currentTarget(bot, wrapper);
            current = adjustForLava(bot, level, pos, current, wrapper);

            BlockState currentState = current == null ? null : level.getBlockState(current);

            // Upstream compared both the position and the block type: the bot has to still be
            // aiming at this block, and it has to still be the same kind of block.
            if (!bot.isBotAlive() || current == null || !pos.equals(current)
                    || currentState.getBlock() != level.getBlockState(pos).getBlock()) {
                finish(bot, ref, taskId[0], pos);
                return;
            }

            BlockState target = level.getBlockState(pos);
            SoundEvent sound = LegacyUtils.breakBlockSound(target);

            // Upstream advanced one fixed stage per run, so every block took twenty ticks
            // whatever the bot held. Progress is now the held tool's destroy speed against this
            // block, which is the whole point of a bot having a tool tier. Block *hardness* is
            // still ignored, as upstream ignored it: a bot tunnels at a rate set by its tools
            // and not by what it is tunnelling through.
            float speed = bot.getMainHandItem().getDestroySpeed(target);
            short next = (short) (progress + Math.max(1, Math.round(speed * BREAK_PERIOD)));

            // Read before the destroy branch, where upstream read it after. Upstream was safe
            // because an unbreakable block never advanced a stage and so never reached nine;
            // progress is no longer capped at one step per run, so a netherite bot would
            // overshoot straight past the check and destroy bedrock.
            boolean unbreakable = UNBREAKABLE.contains(target.getBlock());

            if (!unbreakable && next >= BREAK_COST) {
                if (sound != null) {
                    level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f);
                }

                // Neither drops nor durability follow the held tool, which is worth saying now
                // that the tool can be shears. destroyBlock hands ItemStack.EMPTY to
                // Block.dropResources rather than the breaker's item, so sheared leaves still
                // drop saplings rather than leaf blocks and a bot does not litter a forest with
                // item entities. And nothing in this mod calls mineBlock, the only thing that
                // spends durability, so a bot never snaps its shears after 238 blocks.
                level.destroyBlock(pos, true, bot);

                if (wrapper.get() == ScanOffset.ABOVE) {
                    // Breaking the block overhead then jumping into the hole is how a bot gets
                    // itself stuck, so jumping is suppressed for 15 ticks.
                    state.noJump.add(bot);
                    agent.later(15, () -> state.noJump.remove(bot));
                }

                finish(bot, ref, taskId[0], pos);
                return;
            }

            if (sound != null) {
                level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.3f, 1f);
            }

            // No store, so the task spins here forever rather than stopping. Faithfully
            // wasteful, and unchanged: the sound still plays every run on an unbreakable block.
            if (unbreakable) {
                return;
            }

            if (BlockRules.isInstantBreak(target)) {
                level.destroyBlock(pos, true, bot);
                return;
            }

            BotFactory.broadcastCrack(bot, state.crackList.get(ref), pos,
                    Math.min(STAGES - 1, next / STAGE_COST));
            state.mining.put(taskId[0], next);
        });

        state.mining.put(taskId[0], (short) 0);
    }

    /** Clears the overlay and forgets the block. */
    private void finish(Bot bot, AgentState.BlockRef ref, int taskId, BlockPos pos) {
        Short id = state.crackList.remove(ref);

        if (id != null) {
            BotFactory.broadcastCrack(bot, id, pos, -1);
        }

        state.mining.remove(taskId);
        agent.cancel(taskId);
    }

    /** Where the bot is currently aiming, per the wrapper's offset. */
    private @Nullable BlockPos currentTarget(Bot bot, ScanOffset.Wrapper wrapper) {
        ScanOffset offset = wrapper.get();

        if (offset == null) {
            return BlockPos.containing(bot.position()).above();
        }

        if (offset == ScanOffset.BELOW) {
            List<BlockPos> standing = bot.getStandingOn();
            return standing.isEmpty() ? null : standing.get(0);
        }

        return offset.apply(BlockPos.containing(bot.position()));
    }

    /**
     * Re-aims a bot suspended over lava, and reports where it is now aiming.
     *
     * <p>Ported from the two near-identical blocks in the middle of upstream's
     * {@code blockBreakEffect}. One handles lava two blocks below with the bot aiming one above
     * the target, the other lava one block below with the bot aiming one below. Both walk the
     * {@link ScanOffset} ladder and re-pitch.
     */
    private @Nullable BlockPos adjustForLava(Bot bot, ServerLevel level, BlockPos block,
                                             @Nullable BlockPos current, ScanOffset.Wrapper wrapper) {
        ScanOffset offset = wrapper.get();

        if (offset == null || current == null) {
            return current;
        }

        BlockPos botPos = BlockPos.containing(bot.position());

        if ((offset.isSideAt() || offset.isSideUp())
                && level.getBlockState(botPos.below(2)).getBlock() == Blocks.LAVA
                && block.above().equals(current)) {
            wrapper.set(offset.sideDown());
            repitch(bot, wrapper);
            return block;
        }

        if ((offset.isSideAt() || offset.isSideDown())
                && level.getBlockState(botPos.below()).getBlock() == Blocks.LAVA
                && block.below().equals(current)) {
            wrapper.set(offset.sideUp());
            repitch(bot, wrapper);
            return block;
        }

        return current;
    }

    private void repitch(Bot bot, ScanOffset.Wrapper wrapper) {
        ScanOffset offset = wrapper.get();

        if (offset == null) {
            return;
        }

        if (offset.isSideDown() || offset.isSideDown2()) {
            bot.setBotPitch(PITCH_DOWN);
        } else if (offset.isSideUp()) {
            bot.setBotPitch(PITCH_UP);
        } else if (offset.isSide()) {
            bot.setBotPitch(0);
        }
    }

    /**
     * Centres the bot on its own block before it mines the floor out from under itself.
     *
     * <p>Ported from {@code downMine}. Two independent nudges, and the second is not an
     * else-branch: a bot in water gets both, so the dive overwrites the centring velocity.
     *
     * <p>The vector is the offset from the bot to the centre of the block it is standing in.
     * Upstream reads {@code player.getLocation()} twice and mutates the first copy — Bukkit
     * returns a fresh {@code Location} from every call, so the subtraction is between two
     * different objects and the arithmetic is real.
     *
     * <p>Both {@code length() > 1} guards are dead code, upstream included: neither component
     * can exceed half a block, so the vector is never longer than {@code sqrt(0.5)}. Kept
     * because removing a branch is a change, and an unreachable branch costs nothing.
     */
    public void downMine(Bot bot, BlockPos block) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        Vec3 centre = new Vec3(Math.floor(pos.x) + 0.5, pos.y, Math.floor(pos.z) + 0.5);

        if (!BlockRules.isNoCrack(level.getBlockState(block))) {
            MotionVec nudge = MotionVec.of(centre.subtract(pos));

            if (nudge.length() > 1) {
                nudge.normalize();
            }

            nudge.setY(0).multiply(0.1);
            bot.setVelocity(nudge);
        }

        if (bot.isBotInWater()) {
            MotionVec dive = MotionVec.of(centre.subtract(pos));

            if (dive.length() > 1) {
                dive.normalize();
            }

            // Y is set after the scale, so the dive is always exactly one block per tick
            // downward however far off-centre the bot is.
            dive.multiply(0.3).setY(-1);

            if (!state.fallDamageCooldown.contains(bot)) {
                state.fallDamageCooldown.add(bot);
                agent.later(10, () -> state.fallDamageCooldown.remove(bot));
            }

            bot.setVelocity(dive);
        }
    }

    /**
     * Stops a bot's swing animation.
     *
     * <p>Ported from {@code stopMining}. Upstream had this method and three copies of its body
     * inlined elsewhere; {@code Navigation.swim} and {@code BotBehaviors.resetHand} are two of
     * those call sites and they call this instead.
     */
    public void stopMining(Bot bot) {
        Integer task = state.miningAnim.remove(bot);

        if (task != null) {
            agent.cancel(task);
        }
    }

    /**
     * Dumps a water bucket at {@code pos} and picks it back up five ticks later.
     *
     * <p>Ported from {@code placeWaterDown}. {@code BotBehaviors} uses it to put out a fire the
     * bot is standing in. Unlike the MLG in {@code onFallDamage} it has no waterlogging branch —
     * upstream did not write one here, and its call sites are all air or fire.
     *
     * <p>The pickup re-checks the block, so water that has flowed away or been replaced is left
     * alone and the bot silently keeps the empty bucket.
     */
    public void placeWaterDown(Bot bot, BlockPos pos) {
        ServerLevel level = (ServerLevel) bot.level();

        if (level.getBlockState(pos).getBlock() == Blocks.WATER) {
            return;
        }

        bot.look(Direction.DOWN);
        bot.punch();
        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1f, 1f);
        bot.setItem(new ItemStack(Items.BUCKET));

        agent.later(5, () -> {
            if (level.getBlockState(pos).getBlock() != Blocks.WATER) {
                return;
            }

            bot.look(Direction.DOWN);
            bot.setItem(new ItemStack(Items.WATER_BUCKET));
            level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1f, 1f);
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        });
    }

    /**
     * The fastest of {@code tier}'s tools against {@code target}, or an empty hand.
     *
     * <p>Ported from {@code preBreak}'s tool loop. Upstream compared Bukkit's
     * {@code Block.getDestroySpeed(tool)} against a starting value of 1, so a block no tool helps
     * with leaves the bot bare-handed. {@code ItemStack.getDestroySpeed(BlockState)} is the
     * vanilla equivalent and is the tool's multiplier for that block, same orientation.
     *
     * <p>The tier is a parameter rather than the static {@code LegacyItems} list upstream had, so
     * two bots can carry different tools — and since {@link #blockBreakEffect} accrues the held
     * stack's destroy speed, this is also what decides how fast the block comes down.
     *
     * <p>The candidates are {@link EquipmentTier#miningTools()} and not {@code tools()}, so
     * shears are weighed alongside the tier's three. Upstream had no fourth tool and no untiered
     * one. Shears cannot displace a tier tool: they score 1.0 on all but leaves, wool, cobweb,
     * glow lichen and vine, and the comparison below is strictly greater — so they win only
     * where every tier tool also scores 1.0, which is leaves, wool and cobweb. Glow lichen and
     * vine are {@code mineable/axe}, so the axe keeps those on speed or on being iterated first.
     */
    static ItemStack optimalTool(EquipmentTier tier, BlockState target) {
        ItemStack optimal = ItemStack.EMPTY;
        float optimalSpeed = 1;

        for (Item item : tier.miningTools()) {
            ItemStack tool = new ItemStack(item);
            float speed = tool.getDestroySpeed(target);

            if (speed > optimalSpeed) {
                optimal = tool;
                optimalSpeed = speed;
            }
        }

        return optimal;
    }
}
