package net.nuggetmc.tplus.motion;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MotionVecTest {

    @Test
    void mutatorsChangeTheReceiverAndReturnIt() {
        MotionVec v = new MotionVec(1, 2, 3);
        MotionVec returned = v.add(new MotionVec(1, 1, 1));

        assertSame(v, returned, "mutators must return this, like Bukkit Vector");
        assertEquals(2, v.getX());
        assertEquals(3, v.getY());
        assertEquals(4, v.getZ());
    }

    @Test
    void settersMutateInPlace() {
        MotionVec v = new MotionVec(1, 2, 3);
        v.setX(9).setY(8).setZ(7);

        assertEquals(9, v.getX());
        assertEquals(8, v.getY());
        assertEquals(7, v.getZ());
    }

    @Test
    void subtractAndMultiplyMutate() {
        MotionVec v = new MotionVec(4, 4, 4);
        v.subtract(new MotionVec(1, 2, 3)).multiply(2);

        assertEquals(6, v.getX());
        assertEquals(4, v.getY());
        assertEquals(2, v.getZ());
    }

    @Test
    void normalizeOfZeroVectorProducesNaN() {
        // Verified against org.bukkit.util.Vector: normalize() divides by a zero
        // length and yields NaN,NaN,NaN. The ported AI relies on BotMath.clean()
        // and isNotFinite() catching exactly this. Do NOT "fix" it.
        MotionVec v = new MotionVec(0, 0, 0).normalize();

        assertTrue(Double.isNaN(v.getX()));
        assertTrue(Double.isNaN(v.getY()));
        assertTrue(Double.isNaN(v.getZ()));
    }

    @Test
    void normalizeOfNonZeroVectorGivesUnitLength() {
        MotionVec v = new MotionVec(3, 0, 4).normalize();

        assertEquals(0.6, v.getX(), 1e-9);
        assertEquals(0.0, v.getY(), 1e-9);
        assertEquals(0.8, v.getZ(), 1e-9);
        assertEquals(1.0, v.length(), 1e-9);
    }

    @Test
    void lengthAndDotMatchBukkit() {
        assertEquals(5.0, new MotionVec(3, 0, 4).length(), 1e-9);
        assertEquals(25.0, new MotionVec(3, 0, 4).lengthSquared(), 1e-9);
        assertEquals(32.0, new MotionVec(1, 2, 3).dot(new MotionVec(4, 5, 6)), 1e-9);
    }

    @Test
    void copyIsIndependent() {
        MotionVec original = new MotionVec(1, 2, 3);
        MotionVec copy = original.copy();
        copy.setY(99);

        assertNotSame(original, copy);
        assertEquals(2, original.getY(), "copy must not alias the original");
    }

    @Test
    void convertsToAndFromVec3() {
        MotionVec v = MotionVec.of(new Vec3(1.5, 2.5, 3.5));

        assertEquals(1.5, v.getX());
        Vec3 back = v.toVec3();
        assertEquals(2.5, back.y);
        assertEquals(3.5, back.z);
    }
}
