package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;

import java.util.List;

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

    /** Task 17. */
    void blockBreakEffect(Bot bot, BlockPos pos, ScanOffset.Wrapper wrapper) {
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
