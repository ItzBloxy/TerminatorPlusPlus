package net.nuggetmc.tplus.motion;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.text.DecimalFormat;
import java.util.Random;

/**
 * Vector and angle helpers. Ported from the Paper build's {@code MathUtils}; see
 * {@code git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/MathUtils.java}.
 *
 * <p>Bukkit's {@code NumberConversions.isFinite} was verified equivalent to
 * {@link Double#isFinite}, so the JDK method is used here.
 */
public final class BotMath {

    public static final Random RANDOM = new Random();

    private static final DecimalFormat FORMATTER_1 = new DecimalFormat("0.#");
    private static final DecimalFormat FORMATTER_2 = new DecimalFormat("0.##");

    private BotMath() {
    }

    /** Returns {yaw, pitch} in degrees for a direction vector. */
    public static float[] fetchYawPitch(MotionVec dir) {
        double x = dir.getX();
        double z = dir.getZ();

        float[] out = new float[2];

        if (x == 0.0D && z == 0.0D) {
            out[1] = (float) (dir.getY() > 0.0D ? -90 : 90);
        } else {
            double theta = Math.atan2(-x, z);
            out[0] = (float) Math.toDegrees((theta + 6.283185307179586D) % 6.283185307179586D);

            double xz = Math.sqrt(square(x) + square(z));
            out[1] = (float) Math.toDegrees(Math.atan(-dir.getY() / xz));
        }

        return out;
    }

    public static float fetchPitch(MotionVec dir) {
        double x = dir.getX();
        double z = dir.getZ();

        if (x == 0.0D && z == 0.0D) {
            return (float) (dir.getY() > 0.0D ? -90 : 90);
        }

        double xz = Math.sqrt(square(x) + square(z));
        return (float) Math.toDegrees(Math.atan(-dir.getY() / xz));
    }

    /**
     * A random horizontal offset within radius {@code r}.
     *
     * <p>Note the three separate {@link Math#random()} calls: x and z each get their own
     * radius scalar, so this is not a uniform sample of a disc and x/z are not on the
     * same circle. That is what upstream does, and bot spread depends on the resulting
     * distribution, so it is preserved. Do not "correct" it to a single shared radius.
     */
    public static MotionVec circleOffset(double r) {
        double rad = 2 * Math.random() * Math.PI;

        double x = r * Math.random() * Math.cos(rad);
        double z = r * Math.random() * Math.sin(rad);

        return new MotionVec(x, 0, z);
    }

    public static boolean isNotFinite(MotionVec vec) {
        return !Double.isFinite(vec.getX()) || !Double.isFinite(vec.getY()) || !Double.isFinite(vec.getZ());
    }

    /** Zeroes any non-finite component in place. Guards against {@link MotionVec#normalize()} NaN. */
    public static void clean(MotionVec vec) {
        if (!Double.isFinite(vec.getX())) vec.setX(0);
        if (!Double.isFinite(vec.getY())) vec.setY(0);
        if (!Double.isFinite(vec.getZ())) vec.setZ(0);
    }

    /**
     * Block-coordinate X, matching Bukkit's {@code Location.getBlockX}.
     *
     * <p>Named helpers rather than {@code Mth.floor(pos.x)} at the call site: upstream's
     * getBlockX/getBlockY/getBlockZ calls are everywhere in the agent, and the named form keeps
     * the translated code readable against the original.
     */
    public static int floorX(Vec3 vec) {
        return Mth.floor(vec.x);
    }

    /** Block-coordinate Y. */
    public static int floorY(Vec3 vec) {
        return Mth.floor(vec.y);
    }

    /** Block-coordinate Z. */
    public static int floorZ(Vec3 vec) {
        return Mth.floor(vec.z);
    }

    public static double square(double n) {
        return n * n;
    }

    public static double random(double low, double high) {
        return Math.random() * (high - low) + low;
    }

    public static String round1Dec(double n) {
        return FORMATTER_1.format(n);
    }

    public static String round2Dec(double n) {
        return FORMATTER_2.format(n);
    }
}
