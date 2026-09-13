package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Ported from {@code api/agent/legacyagent/LegacyUtils}. */
public final class LegacyUtils {

    private LegacyUtils() {
    }

    /**
     * Whether the straight line from {@code a} to {@code b} passes only through
     * {@link BlockRules#isAir} blocks.
     *
     * <p>Ported from {@code checkFreeSpace}. This is the bot's line of sight: it gates whether a
     * bot attacks, and whether {@code checkDown} and {@code checkSide} decide the target is
     * reachable without mining.
     *
     * <p>The sampling is upstream's and is not a raycast: 32 samples per block of distance, each
     * one converted to a block position and tested. It therefore misses thin diagonal gaps, and
     * it is left exactly as it was.
     *
     * <p>One deviation. Upstream divided by the vector's length without checking it, so two
     * identical points gave a NaN step and a NaN loop bound; the body never ran and it returned
     * true. That works only because every NaN comparison is false, which is too fragile to rely
     * on deliberately, so the zero case returns true explicitly.
     */
    public static boolean checkFreeSpace(ServerLevel level, Vec3 a, Vec3 b) {
        Vec3 v = b.subtract(a);
        double length = v.length();

        if (length == 0) {
            return true;
        }

        int n = 32;
        double m = 1 / (double) n;

        double j = Math.floor(length * n);
        Vec3 step = v.scale(m / length);

        for (int i = 0; i <= j; i++) {
            BlockPos pos = BlockPos.containing(a.add(step.scale(i)));
            BlockState state = level.getBlockState(pos);

            if (!BlockRules.isAir(state)) {
                return false;
            }
        }

        return true;
    }

    /** The break sound for a block. Upstream: {@code getBlockData().getSoundGroup()}. */
    public static SoundEvent breakBlockSound(BlockState state) {
        return state.getSoundType().getBreakSound();
    }
}
