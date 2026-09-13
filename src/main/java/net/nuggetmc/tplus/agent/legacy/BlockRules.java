package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * The block predicates the agent decides with. Replaces {@code LegacyMats} (489 lines of
 * hand-maintained {@code Material} lists).
 *
 * <p><b>Partial.</b> This is the slice {@code Navigation} needs — {@code AIR} and {@code WATER}.
 * Task 13 adds the other ten sets and the solidity predicates; Task 14 adds the placement
 * predicates in {@code BlockPlacement}. Nothing here is provisional, there is just less of it
 * than there will be.
 *
 * <p>Where upstream's list is a closed enumeration it stays an explicit {@code Set<Block>}, and
 * where it is really a category it becomes a {@code BlockTags} lookup (task 13). Tags are the
 * reason this is worth doing: the {@code Material.CHAIN} to {@code IRON_CHAIN} rename is exactly
 * the breakage a tag would have absorbed, and this port walked into it once already.
 */
public final class BlockRules {

    /**
     * Blocks a bot can see and move through.
     *
     * <p>Not "is air" — upstream's set includes water, lava, fire, snow layers, vines and every
     * tall plant, because the question it answers is "can a line of sight or a body pass". Ported
     * as an explicit set because it is a curated list, not a category; the duplicate
     * {@code Material.FIRE} entry upstream had is dropped, since a {@code Set} deduplicates it
     * anyway.
     */
    private static final Set<Block> AIR = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.WATER, Blocks.LAVA,
            Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.SNOW,
            Blocks.VINE,
            Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.SUNFLOWER);

    /**
     * Water, and the plants that only exist in it.
     *
     * <p>Upstream's set. Seagrass and kelp are included because a bot standing in them is, for
     * the purposes of its swim logic, in water.
     */
    private static final Set<Block> WATER = Set.of(
            Blocks.WATER,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT);

    private BlockRules() {
    }

    public static boolean isAir(BlockState state) {
        return AIR.contains(state.getBlock());
    }

    public static boolean isWater(BlockState state) {
        return WATER.contains(state.getBlock());
    }
}
