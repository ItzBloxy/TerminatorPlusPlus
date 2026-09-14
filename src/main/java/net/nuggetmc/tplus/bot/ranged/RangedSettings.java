package net.nuggetmc.tplus.bot.ranged;

/**
 * Every tunable the ranged decision reads, as one immutable value.
 *
 * <p>A record rather than mutable fields on {@code LegacyAgent} (which is what
 * {@code descendRange} is) because {@link RangedDecision#decide} must stay pure: handing it a
 * settings object it cannot mutate is what lets the whole rule be unit tested without a server.
 * Commands replace the whole value through the {@code with} helpers.
 *
 * @param maxRange         how far a bot will shoot. 24 rather than a bow's true reach, because
 *                         the line-of-sight check is priced per block -- see {@code RangedSight}
 * @param towerQuota       how many squadmates must already be towering before a bot shoots instead
 * @param towerQuotaRadius how near the target those towering squadmates must be
 * @param crowdLimit       how many other bots within {@code crowdRadius} make a bot stop shooting
 * @param aloftTicks       consecutive ticks a target must be off the ground to count as flying.
 *                         40 because a player's jump arc is about 12 ticks
 * @param modeMinTicks     how long a mode holds after its rule stops being true
 */
public record RangedSettings(
        RangedOverride override,
        double maxRange,
        int towerQuota,
        double towerQuotaRadius,
        int crowdLimit,
        double crowdRadius,
        int aloftTicks,
        int modeMinTicks) {

    public static final RangedSettings DEFAULTS =
            new RangedSettings(RangedOverride.AUTO, 24.0, 3, 24.0, 4, 4.0, 40, 40);

    public RangedSettings withOverride(RangedOverride value) {
        return new RangedSettings(value, maxRange, towerQuota, towerQuotaRadius,
                crowdLimit, crowdRadius, aloftTicks, modeMinTicks);
    }

    public RangedSettings withTowerQuota(int value) {
        return new RangedSettings(override, maxRange, value, towerQuotaRadius,
                crowdLimit, crowdRadius, aloftTicks, modeMinTicks);
    }
}
