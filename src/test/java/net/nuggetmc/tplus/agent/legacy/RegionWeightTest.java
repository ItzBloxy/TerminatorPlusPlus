package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure: region weighting is arithmetic on an AABB.
 *
 * <p>This is the part of Targeting worth testing in isolation. It decides which targets a bot
 * will even consider, the rules are non-obvious, and it needs no world.
 */
class RegionWeightTest {

    private static final AABB REGION = new AABB(0, 0, 0, 10, 10, 10);

    @Test
    void noRegionMeansNoPenalty() {
        assertEquals(0.0, Targeting.weightedRegionDist(null, new Vec3(500, 500, 500), 1, 1, 1));
    }

    @Test
    void aPointInsideTheRegionHasNoPenalty() {
        assertEquals(0.0, Targeting.weightedRegionDist(REGION, new Vec3(5, 5, 5), 1, 1, 1));
    }

    @Test
    void allZeroWeightsMakeTheRegionAHardBoundary() {
        // Upstream's special case: with every weight at zero, anything outside the region is
        // Double.MAX_VALUE, which validateCloserEntity treats as "not a candidate at all".
        // This is how /tplus region confines bots rather than just biasing them.
        assertEquals(Double.MAX_VALUE,
                Targeting.weightedRegionDist(REGION, new Vec3(20, 5, 5), 0, 0, 0));
        assertEquals(0.0, Targeting.weightedRegionDist(REGION, new Vec3(5, 5, 5), 0, 0, 0));
    }

    @Test
    void thePenaltyIsSquaredDistanceOutsideTheRegionTimesTheWeight() {
        // A 10-wide region centred on 5: half-width 5, so x = 20 is 10 outside. 10^2 * 2 = 200.
        assertEquals(200.0, Targeting.weightedRegionDist(REGION, new Vec3(20, 5, 5), 2, 0, 0));
    }

    @Test
    void eachAxisIsWeightedIndependently() {
        // Upstream's point: a region can be soft vertically and hard horizontally, so bots
        // stay in an arena but may still chase someone who jumps.
        assertEquals(0.0, Targeting.weightedRegionDist(REGION, new Vec3(5, 30, 5), 1, 0, 1));
        assertEquals(400.0, Targeting.weightedRegionDist(REGION, new Vec3(5, 30, 5), 0, 1, 0));
    }

    @Test
    void aNonZeroWeightMakesTheBoundarySoftRatherThanHard() {
        // The hard-boundary rule needs ALL THREE weights at zero. One non-zero weight turns
        // the whole region into a bias, including on the axes weighted zero — which is easy to
        // get wrong by testing each axis separately.
        double d = Targeting.weightedRegionDist(REGION, new Vec3(20, 5, 5), 0, 1, 0);

        assertEquals(0.0, d, "outside on x, but x is weighted 0 and the region is not hard");
    }
}
