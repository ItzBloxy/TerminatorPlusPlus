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
 * <p>Ported from {@code Bot.checkStandingOn}. The Paper original gated on a
 * hand-maintained Material list ({@code LegacyMats.isSolid || canStandOn}); this uses
 * the block's shape instead, which covers the same ground without going stale.
 *
 * <p><b>Use the outline shape, not the collision shape.</b> Bukkit's
 * {@code Block.getBoundingBox()} maps to vanilla's {@code getShape}, and several blocks
 * the original explicitly allowed standing on — ladders and vines among them — override
 * only {@code getShape} and have an <em>empty</em> collision shape. Reading
 * {@code getCollisionShape} silently drops them.
 *
 * <p>An empty shape is skipped, matching Bukkit returning an empty BoundingBox that
 * overlaps nothing.
 *
 * <p>Tag membership needs a loaded datapack, so this class is covered by the
 * server-backed tests rather than pure unit tests (spec section 6).
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

                AABB blockBox = outlineBox(level, pos);
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

                AABB shape = outlineBox(level, pos);
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
     * The block's outline shape in world coordinates, or null when it has none.
     *
     * <p>{@code VoxelShape.bounds()} throws on an empty shape, so the emptiness check is
     * mandatory, not defensive.
     */
    private static AABB outlineBox(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }

        VoxelShape shape = state.getShape(level, pos);
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
