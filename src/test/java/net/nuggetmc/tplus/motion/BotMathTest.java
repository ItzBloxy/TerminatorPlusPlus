package net.nuggetmc.tplus.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BotMathTest {

    @Test
    void yawPitchLookingDueSouth() {
        // +Z is south; Minecraft yaw 0 faces south.
        float[] out = BotMath.fetchYawPitch(new MotionVec(0, 0, 1));

        assertEquals(0.0f, out[0], 1e-4);
        assertEquals(0.0f, out[1], 1e-4);
    }

    @Test
    void yawPitchLookingDueWest() {
        // atan2(-x, z) with x=-1, z=0 gives +pi/2 -> 90 degrees.
        float[] out = BotMath.fetchYawPitch(new MotionVec(-1, 0, 0));

        assertEquals(90.0f, out[0], 1e-4);
    }

    @Test
    void straightUpAndDownAreVerticalSpecialCases() {
        assertEquals(-90.0f, BotMath.fetchYawPitch(new MotionVec(0, 1, 0))[1], 1e-4);
        assertEquals(90.0f, BotMath.fetchYawPitch(new MotionVec(0, -1, 0))[1], 1e-4);
        assertEquals(-90.0f, BotMath.fetchPitch(new MotionVec(0, 1, 0)), 1e-4);
        assertEquals(90.0f, BotMath.fetchPitch(new MotionVec(0, -1, 0)), 1e-4);
    }

    @Test
    void pitchIsNegativeWhenLookingUpwards() {
        // Direction up and forward: pitch = toDegrees(atan(-y / xz)) = -45.
        assertEquals(-45.0f, BotMath.fetchPitch(new MotionVec(0, 1, 1)), 1e-4);
    }

    @Test
    void isNotFiniteDetectsNaNAndInfinity() {
        assertFalse(BotMath.isNotFinite(new MotionVec(1, 2, 3)));
        assertTrue(BotMath.isNotFinite(new MotionVec(Double.NaN, 0, 0)));
        assertTrue(BotMath.isNotFinite(new MotionVec(0, Double.POSITIVE_INFINITY, 0)));
        assertTrue(BotMath.isNotFinite(new MotionVec(0, 0, Double.NEGATIVE_INFINITY)));
    }

    @Test
    void cleanZeroesOnlyTheNonFiniteComponents() {
        MotionVec v = new MotionVec(Double.NaN, 5, Double.POSITIVE_INFINITY);
        BotMath.clean(v);

        assertEquals(0.0, v.getX());
        assertEquals(5.0, v.getY(), "finite components must be left alone");
        assertEquals(0.0, v.getZ());
    }

    @Test
    void circleOffsetStaysInsideRadiusAndIsFlat() {
        for (int i = 0; i < 200; i++) {
            MotionVec v = BotMath.circleOffset(3);

            assertEquals(0.0, v.getY(), "offset must be horizontal");
            assertTrue(v.length() <= 3.0 + 1e-9, "offset escaped radius: " + v);
        }
    }

    @Test
    void squareMatchesBukkitNumberConversions() {
        assertEquals(9.0, BotMath.square(-3), 1e-9);
    }
}
