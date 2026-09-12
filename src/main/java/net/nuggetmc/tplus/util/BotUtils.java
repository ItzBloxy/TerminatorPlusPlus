package net.nuggetmc.tplus.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Ported from {@code api/utils/BotUtils}.
 *
 * <p>{@code randomSteveUUID} moved to {@link net.nuggetmc.tplus.bot.BotGameProfiles} in Plan A,
 * and {@code overlaps} is not ported: it was a one-line alias for {@code BoundingBox.overlaps},
 * and both of its callers now use {@code AABB.intersects} directly.
 */
public final class BotUtils {

    /**
     * Blocks that cancel fall damage. Ported from {@code BotUtils.NO_FALL}; the Paper build
     * listed Materials, these are the equivalent Blocks.
     *
     * <p>Lived in {@code Bot} through Plan A, which had the only caller. Phase 7's
     * {@code BlockScan} needs it too, so it moves to its upstream address.
     */
    public static final Set<Block> NO_FALL = Set.of(
            Blocks.WATER, Blocks.LAVA,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
            Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
            Blocks.COBWEB, Blocks.VINE);

    private BotUtils() {
    }

    /**
     * Squared horizontal distance from the centre of {@code blockPos} to {@code pos}.
     *
     * <p>The {@code + 0.5} is upstream's and is load-bearing: a Bukkit Block's Location is its
     * minimum corner, so without it every proximity sort is biased half a block. Callers only
     * compare results, so the root is never taken.
     */
    public static double getHorizSqDist(BlockPos blockPos, Vec3 pos) {
        double dx = blockPos.getX() + 0.5 - pos.x;
        double dz = blockPos.getZ() + 0.5 - pos.z;

        return dx * dx + dz * dz;
    }
}
