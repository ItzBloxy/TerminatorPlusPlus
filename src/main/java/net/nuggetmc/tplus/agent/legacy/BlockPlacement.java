package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.OptionalDouble;
import java.util.Set;

/**
 * Where a bot can put water, or twisting vines, to survive a fall.
 *
 * <p>Ported from {@code LegacyMats.canPlaceWater}, {@code canPlaceTwistingVines} and
 * {@code shouldReplace}. Split out of {@link BlockRules} because these are property decision
 * trees rather than category tests, and 170 lines of nested state checks in the same file as the
 * sets would make both unreviewable.
 *
 * <p>The shape of each method is upstream's: a solid branch that lists the shapes the clutch
 * block will <b>not</b> sit on, and a non-solid branch that lists the few it will.
 */
public final class BlockPlacement {

    /** Upstream's non-solid switch in {@code canPlaceWater}, as a set. */
    private static final Set<Block> STILL_HOLDS_WATER = Set.of(
            Blocks.SNOW,
            Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
            Blocks.CHORUS_FLOWER, Blocks.CHORUS_PLANT,
            Blocks.COCOA,
            Blocks.LILY_PAD, Blocks.SEA_PICKLE,
            Blocks.END_ROD, Blocks.FLOWER_POT,
            Blocks.SCAFFOLDING,
            Blocks.COMPARATOR, Blocks.REPEATER);

    /**
     * Upstream's 35-entry switch in {@code canPlaceTwistingVines}, minus the tagged families.
     *
     * <p>The obvious compression for this whole branch is
     * {@code state.isFaceSturdy(level, pos, Direction.UP)} — "is the top face a full square" —
     * and it is <b>wrong</b>: it disagrees with upstream on farmland, honey blocks, leaves and
     * the end portal frame, all full cubes that upstream rejects anyway. Measured, not guessed.
     */
    private static final Set<Block> NO_VINE_SURFACE = Set.of(
            Blocks.POINTED_DRIPSTONE,
            Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
            Blocks.AMETHYST_CLUSTER,
            Blocks.BAMBOO, Blocks.CACTUS,
            Blocks.DRAGON_EGG, Blocks.TURTLE_EGG,
            Blocks.IRON_CHAIN, Blocks.IRON_BARS,
            Blocks.LANTERN, Blocks.SOUL_LANTERN,
            Blocks.ANVIL, Blocks.BREWING_STAND,
            Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.TRAPPED_CHEST,
            Blocks.ENCHANTING_TABLE, Blocks.GRINDSTONE, Blocks.LECTERN, Blocks.STONECUTTER,
            Blocks.BELL, Blocks.CAKE,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.CAULDRON, Blocks.COMPOSTER, Blocks.CONDUIT,
            Blocks.END_PORTAL_FRAME, Blocks.FARMLAND, Blocks.DAYLIGHT_DETECTOR,
            Blocks.HONEY_BLOCK, Blocks.HOPPER,
            Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER);

    /**
     * Upstream's 17-entry switch in {@code shouldReplace}.
     *
     * <p>Blocks that leave enough of their block space empty for water to occupy it. The set is
     * emphatically <b>not</b> "non-solid blocks": carpets, snow layers, flower pots and heads
     * are all absent, and full-height stairs are present. An earlier draft of this port reused
     * {@link #STILL_HOLDS_WATER} here and got every one of those wrong.
     */
    private static final Set<Block> REPLACEABLE_BY_WATER = Set.of(
            Blocks.POINTED_DRIPSTONE,
            Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
            Blocks.AMETHYST_CLUSTER,
            Blocks.SEA_PICKLE,
            Blocks.LANTERN, Blocks.SOUL_LANTERN,
            Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.TRAPPED_CHEST,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.CONDUIT,
            Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER);

    private BlockPlacement() {
    }

    /**
     * Whether water placed at {@code pos} gives a bot something to land in.
     *
     * @param entityY the falling bot's Y, if known. Upstream passed it for the real MLG and left
     *                it absent for the speculative pre-MLG scan, and one rule reads it: a dry
     *                bottom-half stair only counts when the bot is already inside that block.
     */
    public static boolean canPlaceWater(ServerLevel level, BlockPos pos, OptionalDouble entityY) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (BlockRules.isSolid(state)) {
            boolean waterlogged = state.getValueOrElse(BlockStateProperties.WATERLOGGED, false);

            // A vertical chain: water flows straight past it. Blocks.IRON_CHAIN, not
            // Blocks.CHAIN — see BlockRules.
            if (block == Blocks.IRON_CHAIN && !waterlogged
                    && state.getValueOrElse(BlockStateProperties.AXIS, Direction.Axis.Y) == Direction.Axis.Y) {
                return false;
            }

            // Leaves, mangrove roots, bars and panes: water passes through unless already in.
            if ((state.is(BlockTags.LEAVES) || block == Blocks.MANGROVE_ROOTS
                    || block instanceof IronBarsBlock) && !waterlogged) {
                return false;
            }

            if (state.getValueOrElse(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM) == SlabType.TOP
                    && !waterlogged) {
                return false;
            }

            if (state.is(BlockTags.STAIRS) && !waterlogged) {
                Half half = state.getValueOrElse(BlockStateProperties.HALF, Half.BOTTOM);

                if (half == Half.TOP) {
                    return false;
                }

                // The obscure one. A dry bottom stair is refused unless the bot's own block Y
                // matches the stair's, meaning it is already inside the empty upper half.
                //
                // The (int) cast is upstream's and is WRONG below y = 0, faithfully so: it
                // truncates toward zero, while pos.getY() is a floored block coordinate. A bot
                // at y = -58.6 standing in the block at y = -59 gives -58 != -59, so the rule
                // refuses. Above y = 0 the two agree. BlockRuleTests
                // .the_entity_y_gate_truncates_toward_zero pins the consequence; Mth.floor
                // would fix it and would change clutch behaviour throughout the deepslate
                // layers, which is not this port's call to make.
                if (half == Half.BOTTOM
                        && (entityY.isEmpty() || (int) entityY.getAsDouble() != pos.getY())) {
                    return false;
                }
            }

            if ((state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) && !waterlogged) {
                return false;
            }

            // instanceof, not identity: lightning rods are a weathering-copper family in 26.2
            // and there is no single Blocks.LIGHTNING_ROD constant.
            if (block instanceof LightningRodBlock && !waterlogged) {
                Direction facing = state.getValueOrElse(BlockStateProperties.FACING, Direction.UP);

                if (facing == Direction.UP || facing == Direction.DOWN) {
                    return false;
                }
            }

            if (state.is(BlockTags.TRAPDOORS) && !waterlogged) {
                Half half = state.getValueOrElse(BlockStateProperties.HALF, Half.BOTTOM);
                boolean open = state.getValueOrElse(BlockStateProperties.OPEN, false);

                if (half == Half.TOP || (half == Half.BOTTOM && open)) {
                    return false;
                }
            }

            return true;
        }

        // Non-solid: only the handful of blocks that still hold water at their base.
        return state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.CANDLES)
                || state.is(BlockTags.FLOWER_POTS)
                || isHead(state)
                || STILL_HOLDS_WATER.contains(block);
    }

    /**
     * Whether twisting vines placed at {@code pos} will hold, for a Nether clutch.
     *
     * <p>Stricter than water: vines need a surface beneath them that is not on upstream's
     * reject list.
     */
    public static boolean canPlaceTwistingVines(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (BlockRules.isSolid(state)) {
            return vinesHoldOnSolid(state);
        }

        if (state.getBlock() == Blocks.SNOW) {
            // Upstream: exactly 1 or 8 layers. One layer leaves a full block of space above,
            // eight is a full block — anything between leaves a partial gap the vine falls into.
            int layers = state.getValueOrElse(BlockStateProperties.LAYERS, 1);
            return layers == 1 || layers == 8;
        }

        return SNOW_FREE_VINE_SURFACES.contains(state.getBlock());
    }

    /** Upstream's non-solid switch in {@code canPlaceTwistingVines}, minus the snow case. */
    private static final Set<Block> SNOW_FREE_VINE_SURFACES = Set.of(
            Blocks.CHORUS_FLOWER, Blocks.SCAFFOLDING,
            Blocks.AZALEA, Blocks.FLOWERING_AZALEA);

    /** Upstream's rejection list for the solid branch of {@code canPlaceTwistingVines}. */
    private static boolean vinesHoldOnSolid(BlockState state) {
        Block block = state.getBlock();

        if (state.is(BlockTags.LEAVES) || isCoral(state)
                || block instanceof IronBarsBlock
                || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
                || state.is(BlockTags.BANNERS)
                || state.is(BlockTags.BEDS)
                || state.is(BlockTags.CANDLE_CAKES)
                || state.is(BlockTags.DOORS)
                || state.is(BlockTags.FENCE_GATES)) {
            return false;
        }

        if (state.is(BlockTags.SLABS)
                && state.getValueOrElse(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM) == SlabType.BOTTOM) {
            return false;
        }

        if (state.is(BlockTags.STAIRS)
                && state.getValueOrElse(BlockStateProperties.HALF, Half.BOTTOM) == Half.BOTTOM) {
            return false;
        }

        if (state.is(BlockTags.TRAPDOORS)) {
            boolean bottom = state.getValueOrElse(BlockStateProperties.HALF, Half.BOTTOM) == Half.BOTTOM;

            if (bottom || state.getValueOrElse(BlockStateProperties.OPEN, false)) {
                return false;
            }
        }

        // Pistons by identity rather than class: the base and head block classes were renamed,
        // and the three constants are stable. A head must point up; an extended base must point
        // down.
        if (block == Blocks.PISTON_HEAD
                && state.getValueOrElse(BlockStateProperties.FACING, Direction.UP) != Direction.UP) {
            return false;
        }

        if ((block == Blocks.PISTON || block == Blocks.STICKY_PISTON)
                && state.getValueOrElse(BlockStateProperties.EXTENDED, false)
                && state.getValueOrElse(BlockStateProperties.FACING, Direction.DOWN) != Direction.DOWN) {
            return false;
        }

        return !NO_VINE_SURFACE.contains(block) && !(block instanceof LightningRodBlock);
    }

    /**
     * Whether the clutch block is placed <i>into</i> {@code pos} rather than on top of it.
     *
     * <p>Ported from {@code shouldReplace}. {@code onFallDamage} calls it to choose between
     * {@code ground} and {@code ground.above()}.
     */
    public static boolean shouldReplace(ServerLevel level, BlockPos pos, double entityY, boolean nether) {
        // Two leading gates, both upstream's, both easy to lose in a rewrite. The bot's own
        // block Y must equal the block's, so this only ever replaces a block the bot is already
        // inside; and nothing is ever replaced in the Nether, because twisting vines need a
        // surface to sit on rather than a space to fill.
        //
        // Same faithful (int) truncation as canPlaceWater's stair rule: below y = 0 this
        // compares a toward-zero cast against a floored block coordinate and disagrees by one,
        // so a bot standing anywhere but exactly on a block boundary never replaces. That is
        // upstream's behaviour and it silently disables slab and stair clutches through the
        // whole deepslate range.
        if ((int) entityY != pos.getY() || nether) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        boolean waterlogged = state.getValueOrElse(BlockStateProperties.WATERLOGGED, false);

        if (isCoral(state)) {
            return true;
        }

        if (state.is(BlockTags.SLABS)
                && state.getValueOrElse(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM) == SlabType.BOTTOM) {
            return true;
        }

        // Either half, unlike canPlaceWater's stair rule, which distinguishes them. Upstream
        // tested only waterlogging here; the two methods disagree on purpose.
        if (state.is(BlockTags.STAIRS) && !waterlogged) {
            return true;
        }

        if (block == Blocks.IRON_CHAIN && !waterlogged) {
            return true;
        }

        if (state.is(BlockTags.CANDLES)) {
            return true;
        }

        if (state.is(BlockTags.TRAPDOORS) && !waterlogged) {
            return true;
        }

        return REPLACEABLE_BY_WATER.contains(block) || block instanceof LightningRodBlock;
    }

    /** Coral in any of upstream's three name forms: coral, coral fan, coral wall fan. */
    private static boolean isCoral(BlockState state) {
        return state.is(BlockTags.CORALS) || state.is(BlockTags.CORAL_PLANTS)
                || state.is(BlockTags.WALL_CORALS);
    }

    /** A head or skull, wall-mounted or not. No tag covers these. */
    private static boolean isHead(BlockState state) {
        Block block = state.getBlock();
        return block instanceof SkullBlock || block instanceof WallSkullBlock;
    }
}
