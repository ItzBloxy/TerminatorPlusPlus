package net.nuggetmc.tplus.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure: BlockPos and Vec3 are plain data classes with no registry dependency.
 */
class BotUtilsTest {

    @Test
    void horizontalDistanceIsMeasuredFromTheBlockCentre() {
        // Upstream added 0.5 to the block coordinate because a Block's Location is its
        // minimum corner. A bot standing dead centre on a block is at distance 0, not
        // 0.5 — every sort that orders candidate blocks by proximity depends on this.
        assertEquals(0.0, BotUtils.getHorizSqDist(new BlockPos(10, 64, 20), new Vec3(10.5, 64, 20.5)));
    }

    @Test
    void horizontalDistanceIgnoresY() {
        double flat = BotUtils.getHorizSqDist(new BlockPos(0, 64, 0), new Vec3(3.5, 64, 0.5));
        double high = BotUtils.getHorizSqDist(new BlockPos(0, 64, 0), new Vec3(3.5, 200, 0.5));

        assertEquals(flat, high, "the name says horizontal; Y must not contribute");
        assertEquals(9.0, flat);
    }

    @Test
    void horizontalDistanceIsSquaredNotRooted() {
        // 3 blocks away squares to 9. Callers only ever compare, so upstream never took
        // the root; a port that "helpfully" returns the real distance changes nothing
        // visible but makes every comparison against a squared constant wrong.
        assertEquals(9.0, BotUtils.getHorizSqDist(new BlockPos(3, 0, 0), new Vec3(0.5, 0, 0.5)));
    }

    @Test
    void noFallListedTheBlocksThatCancelFallDamage() {
        assertTrue(BotUtils.NO_FALL.contains(net.minecraft.world.level.block.Blocks.WATER));
        assertTrue(BotUtils.NO_FALL.contains(net.minecraft.world.level.block.Blocks.COBWEB));
        assertTrue(BotUtils.NO_FALL.contains(net.minecraft.world.level.block.Blocks.POWDER_SNOW));
        assertEquals(10, BotUtils.NO_FALL.size(), "upstream BotUtils.NO_FALL had exactly 10 entries");
    }
}
