package net.nuggetmc.tplus.bot.ranged;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where to point a bow so the arrow arrives.
 *
 * <p>An arrow has no closed-form trajectory: {@code AbstractArrow} applies a 0.99 multiplicative
 * drag <i>and</i> a 0.05 gravity subtraction every tick, so the path is an exponential decay with
 * a linear term and the launch angle cannot be solved algebraically. This simulates instead, and
 * binary-searches the angle.
 *
 * <p><b>The integration order is vanilla's and matters.</b> {@code AbstractArrow.tick} moves the
 * arrow, <i>then</i> calls {@code applyInertia(getAirDrag())}, <i>then</i> {@code applyGravity()}.
 * So a tick is: position += velocity; velocity *= 0.99; velocity.y -= 0.05. Reordering those
 * gives an answer that looks plausible and is consistently wrong.
 *
 * <p>Upstream had no ranged combat, so none of this is a translation. The obvious alternative --
 * {@code AbstractSkeleton.performRangedAttack}'s {@code distanceToTarget * 0.2F} fudge -- is not
 * used: it is tuned for the 1.6 launch speed a skeleton uses rather than a bow's 3.0, and it does
 * not lead a moving target at all.
 */
public final class BowBallistics {

    /** {@code AbstractArrow.getDefaultGravity()}. */
    public static final double GRAVITY = 0.05;

    /** {@code AbstractArrow.INERTIA}, returned by {@code getAirDrag()}. */
    public static final double DRAG = 0.99;

    /**
     * How far below the shooter's eye an arrow is born.
     *
     * <p>{@code AbstractArrow(type, mob, level, ...)} delegates to
     * {@code mob.getX(), mob.getEyeY() - 0.1F, mob.getZ()}. Callers must subtract this from the
     * eye before solving, or every shot carries a fixed bias -- and because this class is pure,
     * its tests would happily pin that bias forever.
     */
    public static final double LAUNCH_EYE_OFFSET = 0.1;

    /** {@code BowItem.releaseUsing} passes {@code pow * 3.0F}, and a full draw makes pow 1.0. */
    public static final double FULL_DRAW_POWER = 3.0;

    /** Generous: 24 blocks at 3 blocks/tick is ~10 ticks flat, more when lofted. */
    private static final int MAX_FLIGHT_TICKS = 120;

    /** 18 halvings of a 2-degree bracket converges below 0.00001 degrees. */
    private static final int SEARCH_STEPS = 18;

    /** Stops short of +/-90, where horizontal speed vanishes and the arrow never arrives. */
    private static final double MAX_PITCH = 89.0;

    /**
     * Coarse scan resolution before bisecting. Two degrees is 90 simulations in the worst case
     * and far fewer in practice, because the scan exits at the first crossing and most shots are
     * nearly flat.
     */
    private static final double SCAN_STEP_DEGREES = 2.0;

    /**
     * A launch direction, in Minecraft's rotation convention.
     *
     * @param pitch       degrees, negative looking up
     * @param flightTicks how long the arrow is in the air, used to lead a moving target
     */
    public record Aim(float yaw, float pitch, int flightTicks) {
    }

    private BowBallistics() {
    }

    /**
     * Solves for a launch angle that puts the arrow at {@code target}.
     *
     * @param origin the launch point -- the shooter's eye minus {@link #LAUNCH_EYE_OFFSET}
     * @return null when no angle reaches, which is the caller's signal not to release
     */
    public static @Nullable Aim solve(Vec3 origin, Vec3 target, double power) {
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = target.y - origin.y;

        // Mth.atan2(movement.x, movement.z) is what Projectile.shoot writes back, and
        // shootFromRotation builds xd from -sin(yaw); the two agree on this form.
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        if (horizontal < 1.0e-4) {
            // Straight up or straight down. Nothing to search: the horizontal search below
            // divides by a distance that is effectively zero.
            return new Aim(yaw, dy >= 0 ? -90f : 90f, 1);
        }

        // Arrival height is NOT monotonic in pitch, which a first pass at this got wrong. It is
        // negative infinity at BOTH extremes: at +89 and at -89 the horizontal speed is about
        // 0.05 blocks per tick, so the arrow never covers the distance at all and never has an
        // arrival height. Between those it rises to a peak. A plain bisection over [-89, 89]
        // therefore cannot bracket a root -- it reads "never got there" as "arrived low" and
        // declares every shot out of reach.
        //
        // So: scan coarsely from the flattest angle downward until the curve crosses dy, then
        // bisect inside that one bracket. Scanning downward finds the LOW arc first, which is
        // what we want -- it arrives sooner and is harder to dodge than a lob.
        double previousPitch = MAX_PITCH;
        double previousHeight = heightAt(previousPitch, horizontal, power);

        for (double pitch = MAX_PITCH - SCAN_STEP_DEGREES; pitch >= -MAX_PITCH; pitch -= SCAN_STEP_DEGREES) {
            double height = heightAt(pitch, horizontal, power);

            if (previousHeight <= dy && height >= dy) {
                double solved = bisect(previousPitch, pitch, dy, horizontal, power);
                return new Aim(yaw, (float) solved, flightTicks(solved, horizontal, power));
            }

            previousPitch = pitch;
            previousHeight = height;
        }

        // The curve never reached dy at any angle: out of range, or too far above.
        return null;
    }

    /**
     * Narrows a bracket in which arrival height crosses {@code dy}.
     *
     * <p>{@code low} is the flatter angle (arriving below) and {@code high} the more lofted one
     * (arriving above). Height rises as pitch falls, so the usual inequality reads inverted here.
     */
    private static double bisect(double low, double high, double dy, double horizontal, double power) {
        for (int i = 0; i < SEARCH_STEPS; i++) {
            double mid = (low + high) / 2.0;

            if (heightAt(mid, horizontal, power) < dy) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return (low + high) / 2.0;
    }

    /**
     * Solves once to learn the flight time, then re-solves against where the target will be.
     *
     * <p>One pass, not a fixed point. A second pass changes the answer by less than the arrow's
     * own spread, and a target that accelerates during the flight defeats any number of passes.
     */
    public static @Nullable Aim solveWithLead(Vec3 origin, Vec3 target, Vec3 targetVelocity,
                                              double power) {
        Aim first = solve(origin, target, power);

        if (first == null || targetVelocity.lengthSqr() < 1.0e-9) {
            return first;
        }

        Vec3 led = target.add(targetVelocity.scale(first.flightTicks()));
        Aim second = solve(origin, led, power);

        // Falling back to the un-led solution is deliberate: leading can push the aim point
        // out of reach, and a slightly stale shot beats no shot.
        return second == null ? first : second;
    }

    /**
     * How high the arrow is, relative to its launch point, when it has travelled
     * {@code horizontal} blocks sideways.
     *
     * <p>Package-visible so the tests can re-simulate a solved angle and assert on where the
     * arrow actually arrives rather than on the angle itself.
     */
    static double heightAt(double pitchDegrees, double horizontal, double power) {
        double radians = Math.toRadians(pitchDegrees);

        // shootFromRotation: yd = -sin(pitch), and the horizontal components carry cos(pitch).
        // getMovementToShoot then normalises and scales by power, so the launch speed is exactly
        // `power` and these two components are its resolution.
        double vh = Math.cos(radians) * power;
        double vy = -Math.sin(radians) * power;

        double h = 0;
        double y = 0;

        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            if (vh <= 0) {
                break;
            }

            double nextH = h + vh;

            if (nextH >= horizontal) {
                // Interpolate within the final step rather than overshooting to the tick
                // boundary. At 3 blocks/tick a whole step is 3 blocks of error.
                double fraction = (horizontal - h) / vh;
                return y + vy * fraction;
            }

            h = nextH;
            y += vy;

            // Vanilla's order: move, then drag, then gravity.
            vh *= DRAG;
            vy = vy * DRAG - GRAVITY;
        }

        return Double.NEGATIVE_INFINITY;
    }

    /** Ticks until the arrow has travelled {@code horizontal} blocks sideways. */
    private static int flightTicks(double pitchDegrees, double horizontal, double power) {
        double radians = Math.toRadians(pitchDegrees);
        double vh = Math.cos(radians) * power;

        double h = 0;

        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            h += vh;

            if (h >= horizontal) {
                return tick;
            }

            vh *= DRAG;
        }

        return MAX_FLIGHT_TICKS;
    }
}
