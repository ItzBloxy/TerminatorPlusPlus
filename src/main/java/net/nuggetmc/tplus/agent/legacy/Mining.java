package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
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

    /**
     * The tools a bot will consider. Upstream's {@code LegacyItems}: one iron tool of each kind,
     * so a bot never holds a diamond pickaxe it did not earn.
     *
     * <p>Spec §4.4 folds {@code LegacyItems} into {@code BlockRules}. These are items, and a
     * block-rules class is the wrong home; they live next to the only code that reads them.
     *
     * <p>{@code Item} constants, not {@code ItemStack}s. Constructing a stack in a static
     * initialiser throws "Components not bound yet" — the constructor reads the item's default
     * data components, and those are bound during registry load. As a static field that breaks
     * mod loading outright, which is how this was found. Upstream built its stacks inside the
     * loop too.
     */
    private static final List<Item> TOOLS = List.of(
            Items.IRON_PICKAXE,
            Items.IRON_AXE,
            Items.IRON_SHOVEL);

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

        bot.setItem(optimalTool(target));

        if (offset.isSideDown() || offset.isSideDown2()) {
            bot.setBotPitch(PITCH_DOWN);

            // Upstream forces the same-column flag on five ticks in. It is not a measurement —
            // it is a way of telling the rest of tickBot "this bot is committed, let checkUp
            // and checkSide run". Faithfully odd.
            agent.later(5, () -> state.btCheck.put(bot, true));
        } else if (offset.isSideUp()) {
            bot.setBotPitch(PITCH_UP);
        } else if (offset == ScanOffset.AT_D || offset == ScanOffset.AT) {
            // Aims a block below the target's centre, which points the bot's head at the
            // block's lower face rather than through it.
            bot.faceLocation(Vec3.atCenterOf(pos).add(0, -1, 0));
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
     * <p>Ported from {@code blockBreakEffect}. One task per block, ticking every 2 ticks through
     * ten stages. Three things make it more than a counter:
     *
     * <ul>
     * <li>It re-derives its target from the bot's <em>current</em> position each tick, through
     *     {@code wrapper}'s offset, and cancels if that no longer matches the block it started
     *     on. That is how mining stops when the bot moves.
     * <li>If the bot is suspended over lava it re-aims one rung up or down the {@link ScanOffset}
     *     ladder and keeps going, mutating the wrapper so the change persists. Upstream's
     *     comment: "Fix boat clutching while breaking block. As a side effect, the bot is able to
     *     break multiple blocks at once while over lava."
     * <li>Six block types are hard-refused at the last moment — bedrock, barrier, command blocks,
     *     the end portal frame, the structure block. It returns without advancing, so the task
     *     spins forever on them rather than stopping. Faithfully wasteful.
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

        taskId[0] = agent.repeating(2, () -> {
            byte stage = state.mining.getOrDefault(taskId[0], (byte) 0);

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

            SoundEvent sound = LegacyUtils.breakBlockSound(level.getBlockState(pos));

            if (stage == 9) {
                if (sound != null) {
                    level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f);
                }

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

            if (UNBREAKABLE.contains(level.getBlockState(pos).getBlock())) {
                return;
            }

            if (BlockRules.isInstantBreak(level.getBlockState(pos))) {
                level.destroyBlock(pos, true, bot);
                return;
            }

            BotFactory.broadcastCrack(bot, state.crackList.get(ref), pos, stage);
            state.mining.put(taskId[0], (byte) (stage + 1));
        });

        state.mining.put(taskId[0], (byte) 0);
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
     * The fastest of the three tools against {@code target}, or an empty hand.
     *
     * <p>Ported from {@code preBreak}'s tool loop. Upstream compared Bukkit's
     * {@code Block.getDestroySpeed(tool)} against a starting value of 1, so a block no tool helps
     * with leaves the bot bare-handed. {@code ItemStack.getDestroySpeed(BlockState)} is the
     * vanilla equivalent and is the tool's multiplier for that block, same orientation.
     */
    static ItemStack optimalTool(BlockState target) {
        ItemStack optimal = ItemStack.EMPTY;
        float optimalSpeed = 1;

        for (Item item : TOOLS) {
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
