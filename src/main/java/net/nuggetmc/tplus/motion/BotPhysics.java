package net.nuggetmc.tplus.motion;

/**
 * Per-tick velocity integration, extracted from the Paper build's
 * {@code Bot.updateLocation} and {@code Bot.addFriction} so it can be tested without a
 * world. Constants are reproduced exactly; do not tune them here.
 */
public final class BotPhysics {

    private static final double GRAVITY = 0.08;
    private static final double TERMINAL_VELOCITY = -3.5;
    private static final double GROUND_FRICTION = 0.5;
    private static final double WATER_FRICTION = 0.8;
    private static final double WATER_BUOYANCY = 0.1;
    private static final double FRICTION_MIN = 0.01;

    private BotPhysics() {
    }

    /**
     * Advances {@code velocity} one tick in place.
     *
     * @return the Y displacement to apply to the entity this tick, which is not always
     *         {@code velocity.getY()} — on the ground it is zeroed, and in air the
     *         stored Y is the value for the <em>next</em> tick.
     */
    public static double step(MotionVec velocity, byte groundTicks, byte jumpTicks, boolean inWater) {
        BotMath.clean(velocity);

        double y;

        if (inWater) {
            y = Math.min(velocity.getY() + WATER_BUOYANCY, WATER_BUOYANCY);
            addFriction(velocity, WATER_FRICTION);
            velocity.setY(y);
        } else if (groundTicks != 0) {
            velocity.setY(0);
            addFriction(velocity, GROUND_FRICTION);
            y = 0;
        } else {
            y = velocity.getY();
            if (jumpTicks - 3 <= 0) {
                velocity.setY(Math.max(y - GRAVITY, TERMINAL_VELOCITY));
            }
        }

        return y;
    }

    /** Scales the horizontal components, snapping near-zero values to exactly zero. */
    public static void addFriction(MotionVec velocity, double factor) {
        double x = velocity.getX();
        double z = velocity.getZ();

        velocity.setX(Math.abs(x) < FRICTION_MIN ? 0 : x * factor);
        velocity.setZ(Math.abs(z) < FRICTION_MIN ? 0 : z * factor);
    }
}
