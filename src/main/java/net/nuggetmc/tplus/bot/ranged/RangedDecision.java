package net.nuggetmc.tplus.bot.ranged;

/**
 * Whether a bot should be shooting, and why.
 *
 * <p>Pure: no {@code Level}, no {@code Entity}, no {@code ItemStack}. That is what lets the whole
 * rule be unit tested, and it is the same trade {@code EnemyTarget.matches} makes.
 *
 * <p><b>The ordering below is the design.</b> Gates are evaluated first and unconditionally, so a
 * failed gate drops a bot to MELEE on the tick it fails. Rules are evaluated after, and only they
 * are subject to hysteresis. Swapping those two properties -- letting a gate be held open by the
 * hysteresis window -- would leave a bot standing still, not drawing and not navigating, which is
 * worse than either mode. {@code RangedDecisionTest.everyGateBypassesTheHysteresisWindow} exists
 * to catch exactly that inversion.
 *
 * <p>Upstream had no ranged combat, so none of this is a translation.
 */
public record RangedDecision(RangedMode mode, RangedRule reason) {

    /**
     * The melee floor, and the exact complement of the melee gate.
     *
     * <p>{@code LegacyAgent.attack}'s third condition is {@code distanceTo(target) >= 4} -> skip,
     * so melee fires strictly below 4. Ranged therefore starts <b>at</b> 4, not above it: writing
     * this as a strict {@code >} would leave a hairline band at exactly 4.0 in which a bot neither
     * punches nor draws. Not tunable, because tuning it would desynchronise the two halves.
     */
    public static final double MELEE_FLOOR = 4.0;

    public boolean isRanged() {
        return mode == RangedMode.RANGED;
    }

    public static RangedDecision decide(RangedContext ctx, RangedSettings settings) {
        if (settings.override() == RangedOverride.NEVER) {
            return melee(RangedRule.DISABLED);
        }

        // ---- gates: no hysteresis, checked every tick -----------------------
        if (!ctx.hasBow()) {
            return melee(RangedRule.NO_BOW);
        }

        if (ctx.targetInvincible()) {
            return melee(RangedRule.TARGET_INVINCIBLE);
        }

        if (ctx.distance() < MELEE_FLOOR) {
            return melee(RangedRule.TOO_CLOSE);
        }

        if (ctx.distance() > settings.maxRange()) {
            return melee(RangedRule.TOO_FAR);
        }

        if (!ctx.onGround()) {
            return melee(RangedRule.AIRBORNE);
        }

        // Before line of sight, because crowding is a cheap integer compare and the sight check
        // is the expensive half of the context. Ordering here is a cost decision, not a
        // behavioural one -- both produce MELEE.
        if (ctx.nearbyBots() >= settings.crowdLimit()) {
            return melee(RangedRule.CROWDED);
        }

        if (!ctx.lineOfSight()) {
            return melee(RangedRule.NO_LINE_OF_SIGHT);
        }

        if (settings.override() == RangedOverride.ALWAYS) {
            return ranged(RangedRule.FORCED);
        }

        // ---- rules: any one of these is enough ------------------------------
        if (ctx.targetAloftTicks() >= settings.aloftTicks()) {
            return ranged(RangedRule.TARGET_FLYING);
        }

        if (ctx.towerersNearTarget() >= settings.towerQuota()) {
            return ranged(RangedRule.TOWER_QUOTA);
        }

        if (ctx.botStuck()) {
            return ranged(RangedRule.BOT_STUCK);
        }

        // ---- hysteresis: only reached when no rule fired --------------------
        if (ctx.currentMode() == RangedMode.RANGED && ctx.ticksInMode() < settings.modeMinTicks()) {
            return ranged(RangedRule.HELD);
        }

        return melee(RangedRule.NO_RULE);
    }

    private static RangedDecision melee(RangedRule reason) {
        return new RangedDecision(RangedMode.MELEE, reason);
    }

    private static RangedDecision ranged(RangedRule reason) {
        return new RangedDecision(RangedMode.RANGED, reason);
    }
}
