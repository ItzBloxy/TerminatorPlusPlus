package net.nuggetmc.tplus.motion;

import net.minecraft.world.phys.Vec3;

/**
 * A mutable 3-component vector that mirrors {@code org.bukkit.util.Vector} semantics.
 *
 * <p>Vanilla's {@link Vec3} is immutable. The ported AI mutates vectors in place at
 * roughly 100 call sites; translating those to {@code Vec3} would silently discard the
 * results, with no compiler warning. Every mutator here returns {@code this}, exactly
 * like Bukkit's Vector did.
 *
 * <p>{@link #normalize()} deliberately yields NaN for a zero-length vector, matching
 * Bukkit. Callers guard against that with {@code BotMath.clean} / {@code isNotFinite};
 * making it "safe" would turn those guards into dead code and change bot physics.
 */
public final class MotionVec {

    private double x;
    private double y;
    private double z;

    public MotionVec() {
        this(0, 0, 0);
    }

    public MotionVec(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static MotionVec of(Vec3 vec) {
        return new MotionVec(vec.x, vec.y, vec.z);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public MotionVec setX(double x) {
        this.x = x;
        return this;
    }

    public MotionVec setY(double y) {
        this.y = y;
        return this;
    }

    public MotionVec setZ(double z) {
        this.z = z;
        return this;
    }

    public MotionVec add(MotionVec other) {
        this.x += other.x;
        this.y += other.y;
        this.z += other.z;
        return this;
    }

    public MotionVec add(double dx, double dy, double dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        return this;
    }

    public MotionVec subtract(MotionVec other) {
        this.x -= other.x;
        this.y -= other.y;
        this.z -= other.z;
        return this;
    }

    public MotionVec multiply(double factor) {
        this.x *= factor;
        this.y *= factor;
        this.z *= factor;
        return this;
    }

    /** Matches Bukkit: divides by length, so a zero vector becomes NaN. */
    public MotionVec normalize() {
        double length = length();
        this.x /= length;
        this.y /= length;
        this.z /= length;
        return this;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double dot(MotionVec other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public MotionVec copy() {
        return new MotionVec(x, y, z);
    }

    public Vec3 toVec3() {
        return new Vec3(x, y, z);
    }

    @Override
    public String toString() {
        return "MotionVec(" + x + ", " + y + ", " + z + ")";
    }
}
