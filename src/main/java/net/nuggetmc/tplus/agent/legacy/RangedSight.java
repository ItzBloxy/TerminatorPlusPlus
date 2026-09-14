package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Whether an arrow has a clear line to something.
 *
 * <p>Deliberately <b>not</b> {@link LegacyUtils#checkFreeSpace}, which is the melee check, for
 * two independent reasons. Same two-ray shape; different constants and a different notion of
 * empty. Registered as part of deviation 38.
 *
 * <p><b>It is priced differently.</b> {@code checkFreeSpace} samples 32 points per block of
 * distance. That is affordable for melee because the same 4-block gate that calls it bounds the
 * ray to 128 lookups, and because it only runs on {@code tickDelay(3)}. A 24-block ray at the
 * same density is 768 lookups, doubled when the first ray fails, per bot, per tick. Four samples
 * per block is 96, and a 0.25-block step still cannot pass through a 1.0-wide block — only
 * sub-block geometry reads differently, and {@code checkFreeSpace} already documents that it is
 * not a raycast and misses thin diagonal gaps anyway.
 *
 * <p><b>It disagrees about what is empty.</b> {@code BlockRules.isAir} is a set-membership test
 * whose set contains {@code WATER}, {@code LAVA}, {@code FIRE}, {@code SNOW} and several plants.
 * That is right for its actual job — it is a movement predicate, and a bot can walk or swim
 * through all of those. It is wrong for a projectile: an arrow crossing water drops to
 * {@code AbstractArrow.WATER_INERTIA = 0.6} and falls short, and one crossing lava catches fire.
 * So water and lava block here while the vegetation exemptions are kept.
 *
 * <p>Upstream had no ranged combat, so none of this is a translation.
 */
public final class RangedSight {

    /**
     * Samples per block of distance. {@code checkFreeSpace} uses 32; see the class comment for
     * why this one cannot afford that and why 4 is still sufficient for full blocks.
     */
    public static final int SAMPLES_PER_BLOCK = 4;

    private RangedSight() {
    }

    /** Whether an arrow can fly through this block. */
    public static boolean passable(BlockState state) {
        return BlockRules.isAir(state) && !BlockRules.isWater(state) && !isLava(state);
    }

    private static boolean isLava(BlockState state) {
        return state.getBlock() == Blocks.LAVA;
    }

    /**
     * Whether the straight line from {@code a} to {@code b} is passable for an arrow.
     *
     * <p>Two identical points are trivially clear, matching {@code checkFreeSpace}'s explicit
     * zero case — which exists there because upstream divided by an unchecked length and relied
     * on every NaN comparison being false.
     */
    public static boolean clear(ServerLevel level, Vec3 a, Vec3 b) {
        Vec3 v = b.subtract(a);
        double length = v.length();

        if (length == 0) {
            return true;
        }

        int steps = (int) Math.floor(length * SAMPLES_PER_BLOCK);
        Vec3 step = v.scale(1.0 / (length * SAMPLES_PER_BLOCK));

        for (int i = 0; i <= steps; i++) {
            BlockPos pos = BlockPos.containing(a.add(step.scale(i)));

            if (!passable(level.getBlockState(pos))) {
                return false;
            }
        }

        return true;
    }

    /**
     * The two-ray check, mirroring what {@code tickBot} does before a melee swing: eye-to-eye or
     * eye-to-feet, because a target behind a half-height wall is still shootable over it.
     */
    public static boolean canSee(ServerLevel level, Vec3 eye, Entity target) {
        return clear(level, eye, target.getEyePosition()) || clear(level, eye, target.position());
    }
}
