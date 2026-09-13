package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * The block predicates the agent decides with. Replaces {@code LegacyMats} (489 lines of
 * hand-maintained {@code Material} lists).
 *
 * <p>The placement predicates — where a bot may put water or twisting vines — live in
 * {@code BlockPlacement}: they are {@code BlockState} decision trees rather than category tests,
 * and 170 more lines of nested property checks in this file would make both unreviewable.
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

    /**
     * Blocks a bot considers already-clear space, so it does not try to mine them.
     *
     * <p>Upstream's BREAK set. Note that it is <b>not</b> the same as {@link #isAir}:
     * SHORT_GRASS is in AIR but not here, so a bot sees through short grass and still mines it
     * at head height. Kept.
     */
    private static final Set<Block> BREAK = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR,
            Blocks.WATER, Blocks.LAVA,
            Blocks.TALL_GRASS,
            Blocks.VINE,
            Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.SUGAR_CANE,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.WEEPING_VINES,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.SUNFLOWER, Blocks.TORCHFLOWER, Blocks.PITCHER_PLANT,
            Blocks.FIRE, Blocks.SOUL_FIRE);

    /** Blocks that get no break animation, and are never "broken" by the mining code. */
    private static final Set<Block> NO_CRACK = Set.of(
            Blocks.WATER, Blocks.LAVA,
            Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR);

    /**
     * Blocks a bot may safely overwrite when it places cobblestone. Upstream's SPAWN set.
     *
     * <p>Water is deliberately absent, even though the near-identical FALL set includes it —
     * so a bot will not seal itself into a pool. FALL itself is not ported: it has no caller
     * anywhere on {@code master}.
     */
    private static final Set<Block> SPAWN = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR,
            Blocks.TALL_GRASS, Blocks.SNOW,
            Blocks.VINE,
            Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.SUGAR_CANE,
            Blocks.TWISTING_VINES, Blocks.WEEPING_VINES,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.SUNFLOWER,
            Blocks.FIRE, Blocks.SOUL_FIRE);

    /**
     * Blocks that must be broken rather than walked through. Upstream's OBSTACLES set.
     *
     * <p>{@code Blocks.IRON_CHAIN}, not {@code Blocks.CHAIN}: that rename is the one spec §4.2
     * cites as the argument for tags, and this port walked straight into it once.
     */
    private static final Set<Block> OBSTACLE_BLOCKS = Set.of(
            Blocks.IRON_BARS,
            Blocks.IRON_CHAIN,
            Blocks.END_ROD,
            Blocks.COBWEB,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.FLOWER_POT,
            Blocks.GLASS_PANE);

    /**
     * Blocks that look like footing but are not, so breaking the block below them is pointless.
     *
     * <p>Upstream's NONSOLID set, with its comment: "We exclude blocks that cannot exist without
     * a solid block below (such as rails or crops)".
     */
    private static final Set<Block> NONSOLID_BLOCKS = Set.of(
            Blocks.COBWEB,
            Blocks.END_GATEWAY, Blocks.END_PORTAL, Blocks.NETHER_PORTAL,
            Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
            Blocks.GLOW_LICHEN, Blocks.HANGING_ROOTS,
            Blocks.POWDER_SNOW,
            Blocks.SCULK_VEIN,
            Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK,
            Blocks.LADDER, Blocks.VINE,
            Blocks.WALL_TORCH, Blocks.SOUL_WALL_TORCH, Blocks.REDSTONE_WALL_TORCH,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT);

    /**
     * Blocks that break in one hit, so the mining loop destroys them without cracking.
     *
     * <p>One deviation from upstream, and the only one in this file. Upstream listed
     * {@code WHEAT_SEEDS} and {@code BEETROOT_SEEDS}, which are <b>items</b> — no block ever
     * matched them, so those two entries were dead. The planted blocks are {@code WHEAT} and
     * {@code BEETROOTS}, which is what this uses.
     */
    private static final Set<Block> INSTANT_BREAK_BLOCKS = Set.of(
            Blocks.TALL_GRASS, Blocks.SHORT_GRASS,
            Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.KELP_PLANT,
            Blocks.DEAD_BUSH,
            Blocks.WHEAT, Blocks.POTATOES, Blocks.CARROTS, Blocks.BEETROOTS,
            Blocks.SEA_PICKLE);

    /** Blocks upstream's {@code canStandOn} listed individually, rather than by name pattern. */
    private static final Set<Block> STANDABLE = Set.of(
            Blocks.END_ROD, Blocks.FLOWER_POT,
            Blocks.REPEATER, Blocks.COMPARATOR,
            Blocks.SNOW, Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING,
            Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.BIG_DRIPLEAF,
            Blocks.CHORUS_FLOWER, Blocks.CHORUS_PLANT, Blocks.COCOA,
            Blocks.LILY_PAD, Blocks.SEA_PICKLE);

    public static boolean isAir(BlockState state) {
        return AIR.contains(state.getBlock());
    }

    public static boolean isWater(BlockState state) {
        return WATER.contains(state.getBlock());
    }

    public static boolean isBreak(BlockState state) {
        return BREAK.contains(state.getBlock());
    }

    public static boolean isNoCrack(BlockState state) {
        return NO_CRACK.contains(state.getBlock());
    }

    public static boolean isSpawn(BlockState state) {
        return SPAWN.contains(state.getBlock());
    }

    public static boolean isInstantBreak(BlockState state) {
        // BlockTags has no SAPLINGS constant; the tag exists but is only reachable through
        // BlockItemTags.SAPLINGS.block(), which is how BlockTags declares its own aliases.
        return INSTANT_BREAK_BLOCKS.contains(state.getBlock())
                || state.is(BlockItemTags.SAPLINGS.block())
                || state.is(BlockTags.CORALS)
                || state.is(BlockTags.WALL_CORALS)
                || state.is(BlockTags.FLOWER_POTS);
    }

    /**
     * Fences and walls — and, faithfully, stained glass panes.
     *
     * <p>Upstream matched Bukkit's {@code Fence} data class, which every pane extends, and
     * excluded only plain {@code GLASS_PANE} and {@code IRON_BARS} by name. So stained panes
     * were in its FENCE set and bots treat them as fences. {@code StainedGlassPaneBlock} extends
     * {@code IronBarsBlock}, so the class test catches them and the two named exclusions remove
     * the rest.
     */
    public static boolean isFence(BlockState state) {
        Block block = state.getBlock();

        if (block == Blocks.GLASS_PANE || block == Blocks.IRON_BARS) {
            return false;
        }

        return state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
                || block instanceof IronBarsBlock;
    }

    public static boolean isGate(BlockState state) {
        return state.is(BlockTags.FENCE_GATES);
    }

    public static boolean isLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES);
    }

    /**
     * Things a bot must break to pass.
     *
     * <p>{@code LightningRodBlock} rather than a constant: rods are a weathering-copper family
     * in 26.2, so there is no single {@code Blocks.LIGHTNING_ROD} to compare against.
     */
    public static boolean isObstacle(BlockState state) {
        return OBSTACLE_BLOCKS.contains(state.getBlock())
                || state.is(BlockTags.FLOWER_POTS)
                || state.getBlock() instanceof LightningRodBlock
                || state.getBlock() instanceof IronBarsBlock;
    }

    public static boolean isNonSolid(BlockState state) {
        return NONSOLID_BLOCKS.contains(state.getBlock())
                || state.is(BlockTags.BUTTONS)
                || state.is(BlockTags.WALL_SIGNS)
                || state.is(BlockTags.ALL_HANGING_SIGNS)
                || state.is(BlockTags.BANNERS)
                || state.is(BlockTags.WALL_CORALS)
                || state.getBlock() instanceof LeverBlock;
    }

    /**
     * Solidity, as upstream meant it.
     *
     * <p>Upstream was {@code mat.isSolid() || SOLID_MATERIALS.contains(mat)}, and
     * {@code SOLID_MATERIALS} is declared and never populated anywhere on {@code master} —
     * check with {@code git grep SOLID_MATERIALS}. So this is just the vanilla predicate.
     */
    public static boolean isSolid(BlockState state) {
        return state.isSolid();
    }

    /**
     * Non-solid blocks that still hold an entity up.
     *
     * <p>Ported from {@code canStandOn}. Upstream's name patterns become tags where one exists:
     * {@code endsWith("_CARPET")} is {@code WOOL_CARPETS}, {@code startsWith("POTTED_")} is
     * {@code FLOWER_POTS}, {@code data == Candle.class} is {@code CANDLES}. Heads and skulls have
     * no tag, so they are a class test — and {@code PISTON_HEAD} is excluded by name, exactly as
     * upstream did, because it matches {@code _HEAD} but is not footing.
     */
    public static boolean canStandOn(BlockState state) {
        Block block = state.getBlock();

        if (block == Blocks.PISTON_HEAD) {
            return false;
        }

        return STANDABLE.contains(block)
                || state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.FLOWER_POTS)
                || state.is(BlockTags.CANDLES)
                || block instanceof SkullBlock
                || block instanceof WallSkullBlock;
    }
}
