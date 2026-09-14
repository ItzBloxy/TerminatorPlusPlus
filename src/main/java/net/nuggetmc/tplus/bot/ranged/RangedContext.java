package net.nuggetmc.tplus.bot.ranged;

/**
 * One bot's world, on one tick, reduced to scalars.
 *
 * <p><b>Every component is a primitive or an enum, deliberately.</b> Constructing an
 * {@code ItemStack} outside a running server throws "Components not bound yet", so a record
 * holding one could never be built in a unit test -- which is exactly the mistake
 * {@code EnemyTarget.matches} avoids by taking an {@code EntityType} and a {@code UUID} rather
 * than an {@code Entity}. Same reasoning, same shape: {@code hasBow} is a boolean, not a bow.
 *
 * @param targetAloftTicks   consecutive ticks the target has been off the ground. Reset by
 *                           {@code Archery} when the target changes, or a grounded target
 *                           inherits a Phantom's count
 * @param towerersNearTarget squadmates currently in {@code AgentState.towerList} and near the target
 * @param botStuck           {@code AgentState.btCheck}: this bot has not left its block column in
 *                           20 ticks
 * @param ticksInMode        how long {@code currentMode} has been held, for hysteresis
 */
public record RangedContext(
        boolean hasBow,
        boolean targetInvincible,
        double distance,
        boolean lineOfSight,
        boolean onGround,
        int nearbyBots,
        int targetAloftTicks,
        int towerersNearTarget,
        boolean botStuck,
        RangedMode currentMode,
        int ticksInMode) {
}
