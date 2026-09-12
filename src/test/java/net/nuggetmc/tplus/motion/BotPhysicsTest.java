package net.nuggetmc.tplus.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BotPhysicsTest {

    @Test
    void frictionScalesHorizontalOnlyAndLeavesYAlone() {
        MotionVec v = new MotionVec(1.0, 5.0, -2.0);
        BotPhysics.addFriction(v, 0.5);

        assertEquals(0.5, v.getX(), 1e-9);
        assertEquals(5.0, v.getY(), 1e-9, "friction must not touch Y");
        assertEquals(-1.0, v.getZ(), 1e-9);
    }

    @Test
    void frictionSnapsTinyHorizontalComponentsToZero() {
        // Below 0.01 the original clamps to exactly zero rather than decaying forever.
        MotionVec v = new MotionVec(0.005, 0, -0.009);
        BotPhysics.addFriction(v, 0.5);

        assertEquals(0.0, v.getX());
        assertEquals(0.0, v.getZ());
    }

    @Test
    void groundedBotHasVerticalMotionZeroedAndFrictionApplied() {
        MotionVec v = new MotionVec(1.0, -0.5, 0);
        double y = BotPhysics.step(v, /*groundTicks*/ (byte) 3, /*jumpTicks*/ (byte) 0, /*inWater*/ false);

        assertEquals(0.0, y);
        assertEquals(0.0, v.getY());
        assertEquals(0.5, v.getX(), 1e-9, "ground friction is 0.5");
    }

    @Test
    void airborneBotAccumulatesGravity() {
        MotionVec v = new MotionVec(0, 0, 0);
        double y = BotPhysics.step(v, (byte) 0, (byte) 0, false);

        assertEquals(0.0, y, "the returned y is the pre-gravity value");
        assertEquals(-0.08, v.getY(), 1e-9, "gravity is subtracted for the next tick");
    }

    @Test
    void fallSpeedIsClampedAtTerminalVelocity() {
        MotionVec v = new MotionVec(0, -3.49, 0);
        BotPhysics.step(v, (byte) 0, (byte) 0, false);

        assertEquals(-3.5, v.getY(), 1e-9, "must not fall faster than -3.5");
    }

    @Test
    void gravityIsSuppressedDuringTheJumpWindow() {
        // jumpTicks starts at 4; while jumpTicks - 3 > 0 the upward impulse is preserved.
        MotionVec v = new MotionVec(0, 0.42, 0);
        BotPhysics.step(v, (byte) 0, (byte) 4, false);

        assertEquals(0.42, v.getY(), 1e-9, "gravity must not eat the jump impulse");
    }

    @Test
    void waterGivesBuoyancyAndStrongerDrag() {
        MotionVec v = new MotionVec(1.0, -1.0, 0);
        double y = BotPhysics.step(v, (byte) 0, (byte) 0, true);

        assertEquals(-0.9, y, 1e-9, "min(vy + 0.1, 0.1)");
        assertEquals(-0.9, v.getY(), 1e-9);
        assertEquals(0.8, v.getX(), 1e-9, "water friction is 0.8");
    }

    @Test
    void buoyancyIsCappedSoBotsDoNotRocketOutOfWater() {
        MotionVec v = new MotionVec(0, 5.0, 0);
        double y = BotPhysics.step(v, (byte) 0, (byte) 0, true);

        assertEquals(0.1, y, 1e-9);
    }
}
