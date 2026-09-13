package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;

import java.util.List;

/**
 * Placing blocks: towering, and the two clutch routines.
 *
 * <p>Ported from {@code LegacyBlockCheck}, which was a real collaborator of {@code LegacyAgent}
 * constructed with {@code (LegacyAgent, Plugin)} and held 8 of the 27 {@code runTaskLater} sites.
 *
 * <p><b>Partial.</b> {@code tryPreMLG} and {@code clutch} land in Task 23.
 */
public final class BlockScan {

    private final AgentState state;
    private final Agent agent;

    public BlockScan(AgentState state, Agent agent) {
        this.state = state;
        this.agent = agent;
    }

    /**
     * Places cobblestone at {@code pos}, shoring up the block below it first when that block is
     * empty too.
     *
     * <p>Ported from {@code placeBlock}. The neighbour tests are upstream's, in upstream's order,
     * and they ask one question in four increasingly desperate ways: <i>is there anything solid
     * nearby for this block to look like it is attached to?</i> A bot towering in mid-air needs a
     * block under the one it stands on, or the placement looks wrong to clients.
     *
     * <p>The delays — 1, 2 and 3 ticks — are upstream's, and are what make the two-block
     * placements look like a player doing it rather than a block appearing.
     *
     * <p>Note that the first branch does <b>not</b> return, which is upstream's and looks like an
     * oversight: a bot with nothing under it both starts the two-block sequence and then falls
     * through into the neighbour tests below, so {@code pos} can be filled immediately and filled
     * again two ticks later. {@code placeFinal} is idempotent, so the only cost is a second sound.
     */
    public void placeBlock(Bot bot, BlockPos pos) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos under = pos.below();

        if (BlockRules.isSpawn(level.getBlockState(under))) {
            placeFinal(bot, under);
            agent.later(2, () -> placeFinal(bot, pos));
        }

        // Any solid neighbour at this level is enough.
        for (BlockPos side : sides(pos)) {
            if (!BlockRules.isSpawn(level.getBlockState(side))) {
                placeFinal(bot, pos);
                return;
            }
        }

        // Failing that, any solid neighbour one level down, plus room beneath for the support.
        boolean edge = false;

        for (BlockPos side : sides(under)) {
            if (!BlockRules.isSpawn(level.getBlockState(side))) {
                edge = true;
            }
        }

        if (edge && BlockRules.isSpawn(level.getBlockState(under))) {
            placeFinal(bot, under);
            agent.later(2, () -> placeFinal(bot, pos));
            return;
        }

        // Failing that, a diagonal, which needs a bridge block placed toward it first.
        boolean bridged = false;

        if (!BlockRules.isSpawn(level.getBlockState(under.offset(1, 0, 1)))
                || !BlockRules.isSpawn(level.getBlockState(under.offset(1, 0, -1)))) {
            BlockPos bridge = under.east();

            if (BlockRules.isSpawn(level.getBlockState(bridge))) {
                placeFinal(bot, bridge);
            }

            bridged = true;
        } else if (!BlockRules.isSpawn(level.getBlockState(under.offset(-1, 0, 1)))
                || !BlockRules.isSpawn(level.getBlockState(under.offset(-1, 0, -1)))) {
            BlockPos bridge = under.west();

            if (BlockRules.isSpawn(level.getBlockState(bridge))) {
                placeFinal(bot, bridge);
            }

            bridged = true;
        }

        if (bridged) {
            agent.later(1, () -> {
                if (BlockRules.isSpawn(level.getBlockState(under))) {
                    placeSound(level, pos);
                    placeFinal(bot, under);
                }
            });

            agent.later(3, () -> {
                placeSound(level, pos);
                placeFinal(bot, pos);
            });
            return;
        }

        placeSound(level, pos);
        placeFinal(bot, pos);
    }

    /**
     * Puts cobblestone at {@code pos}, and turns lava directly below it into cobblestone too.
     *
     * <p>Ported from {@code placeFinal}. The lava rule is upstream's: a bot towering out of a
     * lava lake seals the surface under itself as it goes.
     */
    private void placeFinal(Bot bot, BlockPos pos) {
        ServerLevel level = (ServerLevel) bot.level();

        if (level.getBlockState(pos).getBlock() == Blocks.COBBLESTONE) {
            return;
        }

        placeSound(level, pos);
        bot.setItem(new ItemStack(Items.COBBLESTONE));
        level.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());

        BlockPos under = pos.below();

        if (level.getBlockState(under).getBlock() == Blocks.LAVA) {
            level.setBlockAndUpdate(under, Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    private static void placeSound(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1f, 1f);
    }

    /**
     * The four horizontal neighbours.
     *
     * <p>Upstream built these as a {@code HashSet} and iterated it, so the order was unspecified.
     * A list fixes one. That would be a real difference if the loop body did anything other than
     * set a boolean — the first loop below even returns early where upstream kept iterating —
     * so a side effect added to either loop has to bring back the full pass.
     */
    private static List<BlockPos> sides(BlockPos pos) {
        return List.of(pos.east(), pos.west(), pos.south(), pos.north());
    }
}
