package net.nuggetmc.tplus.motion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out which blocks a bot is standing on.
 *
 * <p>Ported from {@code Bot.checkStandingOn}, which gated on a hand-maintained Material
 * list: {@code LegacyMats.isSolid(mat) || LegacyMats.canStandOn(mat)}.
 *
 * <h2>Which shape counts as ground</h2>
 *
 * <p>This is an approximation of that list, and the choice of shape matters. Measured in
 * 26.2, these blocks have an <em>empty collision</em> shape but a non-empty outline:
 * vines, snow layers, short and tall grass, poppies, torches, rails, pressure plates,
 * cobwebs, powder snow, and sweet berry bushes.
 *
 * <p>Only vines and snow layers are in upstream's {@code canStandOn}. So keying off the
 * outline shape would make a bot treat grass, flowers and torches as solid ground —
 * eleven wrong blocks to buy two right ones. Collision shape is the better proxy, with
 * the two genuine exceptions handled explicitly below.
 *
 * <p>Neither proxy is exactly the original. Getting it right needs the curated predicate
 * ported as {@code BlockRules} (spec section 4.2), which lands with the agent in Plan B;
 * until then this is deliberately the less wrong of the two.
 *
 * <p>An empty shape is skipped, which also matches Bukkit returning an empty BoundingBox
 * that overlaps nothing — and is mandatory, because {@code VoxelShape.bounds()} throws on
 * an empty shape.
 *
 * <p>Tag membership needs a loaded datapack, so this class is covered by the GameTests
 * rather than pure unit tests (spec section 6).
 */
public final class GroundCheck {

    /** How far below the feet to probe for a supporting block. */
    private static final double PROBE_DEPTH = 0.01;

    /** How far below the feet the fence/wall pass probes. */
    private static final double FENCE_PROBE_DEPTH = 0.51;

    /** Fences and walls hold an entity up 0.5 above their own shape. */
    private static final double FENCE_EXTRA_HEIGHT = 1.5;

    private GroundCheck() {
    }

    /**
     * @param box     the bot's bounding box, for horizontal extents
     * @param feetY   the bot's {@code position().y}. Deliberately separate from
     *                {@code box.minY}: the original probed from the entity position, and
     *                the two are not guaranteed to coincide.
     * @param bbHeight the bot's bounding-box height
     * @return supporting block positions, nearest horizontally first; empty when airborne
     */
    public static List<BlockPos> standingOn(ServerLevel level, AABB box, double feetY, double bbHeight) {
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};

        AABB botBox = new AABB(box.minX, feetY - PROBE_DEPTH, box.minZ,
                box.maxX, feetY + bbHeight, box.maxZ);

        List<BlockPos> found = new ArrayList<>();

        for (double x : xs) {
            for (double z : zs) {
                BlockPos pos = BlockPos.containing(x, feetY - PROBE_DEPTH, z);
                if (found.contains(pos)) {
                    continue;
                }

                AABB blockBox = standableBox(level, pos);
                if (blockBox != null && botBox.intersects(blockBox)) {
                    found.add(pos);
                }
            }
        }

        // Fences, walls, gates, glass panes and iron bars support a bot standing above
        // them. LegacyMats.FENCE was built from Fence.class + Wall.class plus GLASS_PANE
        // and IRON_BARS, so the BARS tag is part of the original set, not an addition.
        for (double x : xs) {
            for (double z : zs) {
                BlockPos pos = BlockPos.containing(x, feetY - FENCE_PROBE_DEPTH, z);
                if (found.contains(pos)) {
                    continue;
                }

                // 26.2 removed the single-argument BlockState.is(TagKey); BlockStateBase
                // implements TypedInstance<Block>, so tag tests go through the holder.
                Holder<Block> holder = level.getBlockState(pos).typeHolder();
                if (!holder.is(BlockTags.FENCES)
                        && !holder.is(BlockTags.FENCE_GATES)
                        && !holder.is(BlockTags.WALLS)
                        && !holder.is(BlockTags.BARS)) {
                    continue;
                }

                AABB shape = standableBox(level, pos);
                if (shape == null) {
                    continue;
                }

                AABB tall = new AABB(shape.minX, shape.minY, shape.minZ,
                        shape.maxX, shape.minY + FENCE_EXTRA_HEIGHT, shape.maxZ);

                if (botBox.intersects(tall)) {
                    found.add(pos);
                }
            }
        }

        double cx = (box.minX + box.maxX) / 2;
        double cz = (box.minZ + box.maxZ) / 2;
        found.sort((a, b) -> Double.compare(horizSqDist(a, cx, cz), horizSqDist(b, cx, cz)));

        return found;
    }

    /**
     * The box a bot can rest on, in world coordinates, or null when there is none.
     *
     * <p>Collision shape: what can physically hold an entity up.
     *
     * <p>{@code VoxelShape.bounds()} throws on an empty shape, so the emptiness check is
     * mandatory, not defensive.
     */
    private static AABB standableBox(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }

        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return null;
        }

        return shape.bounds().move(pos);
    }

    private static double horizSqDist(BlockPos pos, double x, double z) {
        double dx = pos.getX() + 0.5 - x;
        double dz = pos.getZ() + 0.5 - z;
        return dx * dx + dz * dz;
    }
}
