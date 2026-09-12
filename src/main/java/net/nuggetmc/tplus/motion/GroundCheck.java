package net.nuggetmc.tplus.motion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out which blocks a bot is standing on.
 *
 * <p>Ported from {@code Bot.checkStandingOn}. The Paper original tested a hand-written
 * Material list; this uses block tags and collision shapes instead. Tag membership is
 * unavailable without a loaded datapack, so this class is covered by the server-backed
 * tests rather than pure unit tests (spec section 6).
 */
public final class GroundCheck {

    /** How far below the feet to probe for a supporting block. */
    private static final double PROBE_DEPTH = 0.01;

    /** Fences and walls are 1.5 blocks tall for collision but 1.0 for their shape. */
    private static final double FENCE_EXTRA_HEIGHT = 1.5;

    private GroundCheck() {
    }

    /**
     * @return supporting block positions, nearest horizontally first; empty when airborne
     */
    public static List<BlockPos> standingOn(ServerLevel level, AABB box, double bbHeight) {
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};

        double feetY = box.minY;
        AABB botBox = new AABB(box.minX, feetY - PROBE_DEPTH, box.minZ,
                box.maxX, feetY + bbHeight, box.maxZ);

        List<BlockPos> found = new ArrayList<>();

        for (double x : xs) {
            for (double z : zs) {
                BlockPos pos = BlockPos.containing(x, feetY - PROBE_DEPTH, z);
                if (found.contains(pos)) {
                    continue;
                }

                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }

                AABB blockBox = state.getCollisionShape(level, pos).bounds().move(pos);
                if (botBox.intersects(blockBox)) {
                    found.add(pos);
                }
            }
        }

        // Fences, walls and gates support a bot standing 0.5 above their block.
        for (double x : xs) {
            for (double z : zs) {
                BlockPos pos = BlockPos.containing(x, feetY - 0.51, z);
                if (found.contains(pos)) {
                    continue;
                }

                // 26.2 removed the single-argument BlockState.is(TagKey). BlockStateBase now
                // implements TypedInstance<Block>, so tag membership goes through the holder.
                BlockState state = level.getBlockState(pos);
                Holder<Block> holder = state.typeHolder();
                if (!holder.is(BlockTags.FENCES) && !holder.is(BlockTags.FENCE_GATES) && !holder.is(BlockTags.WALLS)) {
                    continue;
                }

                AABB shape = state.getCollisionShape(level, pos).bounds().move(pos);
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

    private static double horizSqDist(BlockPos pos, double x, double z) {
        double dx = pos.getX() + 0.5 - x;
        double dz = pos.getZ() + 0.5 - z;
        return dx * dx + dz * dz;
    }
}
