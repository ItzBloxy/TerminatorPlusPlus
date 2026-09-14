package net.nuggetmc.tplus.bot.ranged;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure. {@code Vec3} needs no registry, which is why {@code MotionVecTest} and
 * {@code RegionWeightTest} already use it at this tier.
 *
 * <p>Every assertion re-simulates the solved trajectory and checks where it actually arrives,
 * rather than pinning an angle. Pinning an angle would pass just as happily with the drag and
 * gravity constants swapped.
 */
class BowBallisticsTest {

    private static final double POWER = BowBallistics.FULL_DRAW_POWER;

    /** Allowed vertical miss at the target's horizontal distance. A player hitbox is 1.8 tall. */
    private static final double TOLERANCE = 0.35;

    private static void assertHits(Vec3 origin, Vec3 target) {
        BowBallistics.Aim aim = BowBallistics.solve(origin, target, POWER);
        assertNotNull(aim, "no solution for " + origin + " -> " + target);

        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double arrivedY = origin.y + BowBallistics.heightAt(aim.pitch(), horizontal, POWER);
        double miss = Math.abs(arrivedY - target.y);

        assertTrue(miss <= TOLERANCE,
                "missed by " + miss + " blocks at " + horizontal + " away; pitch " + aim.pitch());
    }

    @Test
    void aLevelTwentyBlockShotLands() {
        assertHits(new Vec3(0, 64, 0), new Vec3(20, 64, 0));
    }

    @Test
    void anUpwardShotLands() {
        assertHits(new Vec3(0, 64, 0), new Vec3(14, 72, 0));
    }

    @Test
    void aDownwardShotLands() {
        assertHits(new Vec3(0, 80, 0), new Vec3(16, 64, 0));
    }

    @Test
    void aDiagonalShotLands() {
        assertHits(new Vec3(0, 64, 0), new Vec3(9, 67, -12));
    }

    @Test
    void aShotBeyondMaxRangeStillSolves() {
        // Deliberately outside RangedSettings.DEFAULTS.maxRange(). The solver has no notion of
        // range -- that is the gate's job -- and a solver that silently refused long shots would
        // make the gate untunable.
        assertHits(new Vec3(0, 64, 0), new Vec3(40, 64, 0));
    }

    @Test
    void aimPointsAtTheTargetHorizontally() {
        BowBallistics.Aim aim = BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(0, 64, 10), POWER);
        assertNotNull(aim);

        // MC yaw: 0 is +Z, and yaw = atan2(-dx, dz). Straight down +Z is yaw 0.
        assertTrue(Math.abs(aim.yaw()) < 0.01, "expected yaw ~0 looking down +Z, got " + aim.yaw());

        BowBallistics.Aim west = BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(-10, 64, 0), POWER);
        assertNotNull(west);
        assertTrue(Math.abs(west.yaw() - 90f) < 0.01, "expected yaw ~90 looking down -X, got " + west.yaw());
    }

    @Test
    void anUnreachableTargetHasNoSolution() {
        // Straight up out of reach: an arrow at 3.0 blocks/tick under 0.05 gravity cannot climb
        // 200 blocks.
        assertNull(BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(5, 264, 0), POWER));
    }

    @Test
    void flightTimeGrowsWithDistance() {
        BowBallistics.Aim near = BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(6, 64, 0), POWER);
        BowBallistics.Aim far = BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(22, 64, 0), POWER);

        assertNotNull(near);
        assertNotNull(far);
        assertTrue(far.flightTicks() > near.flightTicks(),
                "22 blocks must take longer than 6: " + far.flightTicks() + " vs " + near.flightTicks());
    }

    @Test
    void leadingAMovingTargetAimsAheadOfIt() {
        Vec3 origin = new Vec3(0, 64, 0);
        Vec3 target = new Vec3(20, 64, 0);
        Vec3 velocity = new Vec3(0, 0, 0.28); // a sprinting player, ~0.28 blocks/tick

        BowBallistics.Aim still = BowBallistics.solve(origin, target, POWER);
        BowBallistics.Aim led = BowBallistics.solveWithLead(origin, target, velocity, POWER);

        assertNotNull(still);
        assertNotNull(led);

        // The lead must swing the aim toward +Z, which is where the target is going. In MC's
        // convention 0 is +Z and -90 is +X, so a target due east sits at -90 and leading it
        // south moves the yaw UP toward 0. (The first version of this test asserted the
        // opposite, and the solver was right.)
        assertTrue(led.yaw() != still.yaw(), "a moving target must change the aim");
        assertTrue(led.yaw() > still.yaw(),
                "leading a +Z-bound target must raise yaw toward 0; " + led.yaw() + " vs " + still.yaw());
    }

    @Test
    void aStationaryTargetLeadsToTheSameAimAsNoLead() {
        Vec3 origin = new Vec3(0, 64, 0);
        Vec3 target = new Vec3(15, 64, 3);

        BowBallistics.Aim still = BowBallistics.solve(origin, target, POWER);
        BowBallistics.Aim led = BowBallistics.solveWithLead(origin, target, Vec3.ZERO, POWER);

        assertNotNull(still);
        assertNotNull(led);
        assertTrue(Math.abs(still.pitch() - led.pitch()) < 0.001f);
        assertTrue(Math.abs(still.yaw() - led.yaw()) < 0.001f);
    }
}
