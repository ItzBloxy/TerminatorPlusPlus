package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Ported from {@code LegacyWorldManager}, whose class comment — "This is where the respawning
 * queue will be managed" — describes something that was never written. One method.
 */
public final class LevelRules {

    private LevelRules() {
    }

    /**
     * Whether there is nothing but air for the next 25 blocks straight up.
     *
     * <p>{@code Navigation.checkUp} uses it to decide a distant target is not worth towering
     * toward. Note the test is strict air, not {@link BlockRules#isAir} — upstream compared
     * against {@code Material.AIR} here and against its AIR <i>set</i> elsewhere, so a bot under
     * water is not "above ground". Kept, and pinned by a test, because unifying the two looks
     * like tidying and would change when a bot gives up on a distant target.
     */
    public static boolean aboveGround(ServerLevel level, Vec3 pos) {
        BlockPos base = BlockPos.containing(pos);

        for (int y = 1; y < 25; y++) {
            if (!level.getBlockState(base.above(y)).isAir()) {
                return false;
            }
        }

        return true;
    }
}
