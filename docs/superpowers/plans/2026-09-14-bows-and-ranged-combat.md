# Bows and Ranged Combat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give bots a bow they raise instead of towering, when the target is flying, when the squad is already towering, or when the bot is stuck.

**Architecture:** Four pure value types in `bot/ranged` decide *whether* to shoot and *where* to aim, with no `Level` access, so they unit-test in milliseconds. One collaborator, `Archery` in `agent/legacy`, samples the world into those types, owns a per-bot draw state machine, and is wired into `LegacyAgent.tickBot` at exactly two points. Firing bypasses `BowItem.releaseUsing` entirely and spawns the arrow directly.

**Tech Stack:** Java 21, NeoForge for Minecraft 26.2, JUnit Jupiter 6.1.3 (`src/test`), NeoForge testframework GameTests (`src/gametest`), Gradle.

## Global Constraints

- **Design spec:** `docs/superpowers/specs/2026-09-14-bow-and-ranged-combat-design.md`. Every decision below traces to it.
- **Verify vanilla signatures against the patched jar, never a Paper jar:** `build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar`.
- **Never construct an `ItemStack` in a static initialiser.** Throws "Components not bound yet" and breaks mod loading. Hold `Item` constants; build stacks at the call site.
- **`new ItemStack(...)` does not work at the unit tier.** `Items.BOW` resolves; the stack does not. Pure types carry booleans and primitives, never stacks.
- **Every GameTest needs `@TestHolder`.** Without it the test is silently unregistered and the suite still reports green.
- **GameTests share a level and a JVM.** Never assert the level is empty. Clear static or agent-held state in a `finally`.
- **A fresh `ServerPlayer` carries `invulnerableTime = 60` and `noFallTicks = 60`.** Tick ~70 times before any damage assertion.
- **House style:** comments explain *why*, especially where the code looks wrong. Name what upstream did when relevant. Upstream had no ranged combat, so every file here says so.
- **Commit message trailer** on every commit: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
- **Verify commands:** `./gradlew test` (unit), `./gradlew runGameTestServer` (GameTests, ~10s), `./gradlew build` (both + compile).

---

### Task 1: The ranged decision — pure value types

The whole "should this bot shoot" rule, with no world access. Everything else in the plan consumes these names.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/bot/ranged/RangedMode.java`
- Create: `src/main/java/net/nuggetmc/tplus/bot/ranged/RangedOverride.java`
- Create: `src/main/java/net/nuggetmc/tplus/bot/ranged/RangedRule.java`
- Create: `src/main/java/net/nuggetmc/tplus/bot/ranged/RangedSettings.java`
- Create: `src/main/java/net/nuggetmc/tplus/bot/ranged/RangedContext.java`
- Create: `src/main/java/net/nuggetmc/tplus/bot/ranged/RangedDecision.java`
- Test: `src/test/java/net/nuggetmc/tplus/bot/ranged/RangedDecisionTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum RangedMode { MELEE, RANGED }`
  - `enum RangedOverride { AUTO, ALWAYS, NEVER }`
  - `enum RangedRule` with `String label()`
  - `record RangedSettings(RangedOverride override, double maxRange, int towerQuota, double towerQuotaRadius, int crowdLimit, double crowdRadius, int aloftTicks, int modeMinTicks)` with `RangedSettings.DEFAULTS`, `withOverride(RangedOverride)`, `withTowerQuota(int)`
  - `record RangedContext(boolean hasBow, boolean targetInvincible, double distance, boolean lineOfSight, boolean onGround, int nearbyBots, int targetAloftTicks, int towerersNearTarget, boolean botStuck, RangedMode currentMode, int ticksInMode)`
  - `record RangedDecision(RangedMode mode, RangedRule reason)` with `static RangedDecision decide(RangedContext, RangedSettings)`, `boolean isRanged()`, and `static final double MELEE_FLOOR = 4.0`

- [ ] **Step 1: Write the four supporting types**

`src/main/java/net/nuggetmc/tplus/bot/ranged/RangedMode.java`:

```java
package net.nuggetmc.tplus.bot.ranged;

/**
 * Whether a bot is closing to punch or holding position to shoot.
 *
 * <p>Upstream had no ranged combat, so nothing here is a translation.
 */
public enum RangedMode {
    MELEE,
    RANGED
}
```

`src/main/java/net/nuggetmc/tplus/bot/ranged/RangedOverride.java`:

```java
package net.nuggetmc.tplus.bot.ranged;

/** The operator's thumb on the scale, set by {@code /tplus ranged}. */
public enum RangedOverride {
    /** The rules decide. */
    AUTO,
    /** Skip the rules and shoot whenever the gates allow. The gates still apply. */
    ALWAYS,
    /** No bot ever draws. */
    NEVER
}
```

`src/main/java/net/nuggetmc/tplus/bot/ranged/RangedRule.java`:

```java
package net.nuggetmc.tplus.bot.ranged;

/**
 * Why a bot is in the mode it is in.
 *
 * <p>This exists so {@code /tplus info} can print a reason rather than a number. A scored
 * decision would be a little more elegant and would make "why is this bot not shooting?"
 * unanswerable, which is the question an operator actually asks.
 *
 * <p>The values fall into three groups: rules that produce RANGED, gate failures that produce
 * MELEE, and two bookkeeping reasons.
 */
public enum RangedRule {

    // Rules -> RANGED
    TARGET_FLYING("target_flying"),
    TOWER_QUOTA("tower_quota"),
    BOT_STUCK("bot_stuck"),

    // Bookkeeping -> RANGED
    /** {@code /tplus ranged always} skipped the rules. */
    FORCED("forced"),
    /** No rule fired, but hysteresis is holding the bot in RANGED. */
    HELD("held"),

    // Gate failures -> MELEE
    NO_BOW("no_bow"),
    TARGET_INVINCIBLE("target_invincible"),
    TOO_CLOSE("too_close"),
    TOO_FAR("too_far"),
    AIRBORNE("airborne"),
    CROWDED("crowded"),
    NO_LINE_OF_SIGHT("no_line_of_sight"),

    // Bookkeeping -> MELEE
    /** Every gate passed and no rule fired. The ordinary "just chase it" answer. */
    NO_RULE("no_rule"),
    /** {@code /tplus ranged never}. */
    DISABLED("disabled");

    private final String label;

    RangedRule(String label) {
        this.label = label;
    }

    /** snake_case, for command output. */
    public String label() {
        return label;
    }
}
```

`src/main/java/net/nuggetmc/tplus/bot/ranged/RangedSettings.java`:

```java
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
```

- [ ] **Step 2: Write the context record**

`src/main/java/net/nuggetmc/tplus/bot/ranged/RangedContext.java`:

```java
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
```

- [ ] **Step 3: Write the failing tests**

`src/test/java/net/nuggetmc/tplus/bot/ranged/RangedDecisionTest.java`:

```java
package net.nuggetmc.tplus.bot.ranged;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure. The entire ranged rule lives here rather than in the GameTest tier, for the same reason
 * {@code EnemyTargetTest} does: {@link RangedContext} is scalars, so no world is needed and the
 * whole decision table runs in milliseconds.
 */
class RangedDecisionTest {

    private static final RangedSettings S = RangedSettings.DEFAULTS;

    /** Every gate passing, no rule firing, freshly in MELEE. The baseline every test perturbs. */
    private static RangedContext base() {
        return new RangedContext(true, false, 10.0, true, true, 0, 0, 0, false,
                RangedMode.MELEE, 0);
    }

    private static RangedContext with(RangedContext c, java.util.function.UnaryOperator<RangedContext> f) {
        return f.apply(c);
    }

    @Test
    void everyGatePassingAndNoRuleFiringIsMelee() {
        RangedDecision d = RangedDecision.decide(base(), S);

        assertEquals(RangedMode.MELEE, d.mode());
        assertEquals(RangedRule.NO_RULE, d.reason());
        assertFalse(d.isRanged());
    }

    @Test
    void aFlyingTargetIsShotAt() {
        RangedContext c = new RangedContext(true, false, 10.0, true, true, 0, 40, 0, false,
                RangedMode.MELEE, 0);

        RangedDecision d = RangedDecision.decide(c, S);

        assertTrue(d.isRanged());
        assertEquals(RangedRule.TARGET_FLYING, d.reason());
    }

    @Test
    void aTargetOneTickShortOfTheAloftThresholdIsNot() {
        // 39 ticks, not 40. A player's jump arc is ~12 ticks, so the threshold is what stops a
        // hopping target reading as a Phantom.
        RangedContext c = new RangedContext(true, false, 10.0, true, true, 0, 39, 0, false,
                RangedMode.MELEE, 0);

        assertFalse(RangedDecision.decide(c, S).isRanged());
    }

    @Test
    void enoughSquadmatesToweringMakesTheRestShoot() {
        RangedContext c = new RangedContext(true, false, 10.0, true, true, 0, 0, 3, false,
                RangedMode.MELEE, 0);

        RangedDecision d = RangedDecision.decide(c, S);

        assertTrue(d.isRanged());
        assertEquals(RangedRule.TOWER_QUOTA, d.reason());
    }

    @Test
    void aStuckBotShoots() {
        RangedContext c = new RangedContext(true, false, 10.0, true, true, 0, 0, 0, true,
                RangedMode.MELEE, 0);

        RangedDecision d = RangedDecision.decide(c, S);

        assertTrue(d.isRanged());
        assertEquals(RangedRule.BOT_STUCK, d.reason());
    }

    @Test
    void crowdingBeatsTheTowerQuota() {
        // The conflict the spec calls out: twenty bots on one target, plenty towering, but this
        // one is packed in the middle. It charges rather than firing into its own squad's backs,
        // and charging is what de-crowds it.
        RangedContext c = new RangedContext(true, false, 10.0, true, true, 4, 0, 10, false,
                RangedMode.MELEE, 0);

        RangedDecision d = RangedDecision.decide(c, S);

        assertFalse(d.isRanged());
        assertEquals(RangedRule.CROWDED, d.reason());
    }

    @Test
    void aBotWithNoBowNeverShoots() {
        RangedContext c = new RangedContext(false, false, 10.0, true, true, 0, 40, 0, true,
                RangedMode.MELEE, 0);

        assertEquals(RangedRule.NO_BOW, RangedDecision.decide(c, S).reason());
    }

    @Test
    void anInvincibleTargetIsNotShotAt() {
        RangedContext c = new RangedContext(true, true, 10.0, true, true, 0, 40, 0, false,
                RangedMode.MELEE, 0);

        assertEquals(RangedRule.TARGET_INVINCIBLE, RangedDecision.decide(c, S).reason());
    }

    @Test
    void theMeleeFloorIsTheExactComplementOfTheMeleeGate() {
        // LegacyAgent.attack skips at `distanceTo(target) >= 4`, so melee fires strictly below 4
        // and ranged at or above it. Writing this `> 4` would leave a hairline gap at exactly
        // 4.0 in which a bot does neither.
        RangedContext justInside = new RangedContext(true, false, 3.999, true, true, 0, 40, 0,
                false, RangedMode.MELEE, 0);
        RangedContext exactlyFour = new RangedContext(true, false, 4.0, true, true, 0, 40, 0,
                false, RangedMode.MELEE, 0);

        assertEquals(RangedRule.TOO_CLOSE, RangedDecision.decide(justInside, S).reason());
        assertTrue(RangedDecision.decide(exactlyFour, S).isRanged());
    }

    @Test
    void beyondMaxRangeIsMelee() {
        RangedContext c = new RangedContext(true, false, 24.001, true, true, 0, 40, 0, false,
                RangedMode.MELEE, 0);

        assertEquals(RangedRule.TOO_FAR, RangedDecision.decide(c, S).reason());
    }

    @Test
    void anAirborneBotDoesNotShoot() {
        RangedContext c = new RangedContext(true, false, 10.0, true, false, 0, 40, 0, false,
                RangedMode.MELEE, 0);

        assertEquals(RangedRule.AIRBORNE, RangedDecision.decide(c, S).reason());
    }

    @Test
    void noLineOfSightIsMelee() {
        RangedContext c = new RangedContext(true, false, 10.0, false, true, 0, 40, 0, false,
                RangedMode.MELEE, 0);

        assertEquals(RangedRule.NO_LINE_OF_SIGHT, RangedDecision.decide(c, S).reason());
    }

    @Test
    void hysteresisHoldsRangedWhenTheRuleStopsBeingTrue() {
        // Was flying, has landed, but only 10 ticks into RANGED. Keeps firing rather than
        // flickering in and out of navigation.
        RangedContext c = new RangedContext(true, false, 10.0, true, true, 0, 0, 0, false,
                RangedMode.RANGED, 10);

        RangedDecision d = RangedDecision.decide(c, S);

        assertTrue(d.isRanged());
        assertEquals(RangedRule.HELD, d.reason());
    }

    @Test
    void hysteresisExpires() {
        RangedContext c = new RangedContext(true, false, 10.0, true, true, 0, 0, 0, false,
                RangedMode.RANGED, 40);

        assertFalse(RangedDecision.decide(c, S).isRanged());
    }

    @Test
    void everyGateBypassesTheHysteresisWindow() {
        // The split this whole design turns on: rules hysteresise, gates do not. A bot one tick
        // into RANGED still drops to MELEE the instant any gate fails, because holding RANGED
        // through a failed gate means standing still AND not shooting -- worse than either.
        record Case(String name, RangedContext ctx, RangedRule expected) {
        }

        java.util.List<Case> cases = java.util.List.of(
                new Case("no bow",
                        new RangedContext(false, false, 10, true, true, 0, 40, 0, false, RangedMode.RANGED, 1),
                        RangedRule.NO_BOW),
                new Case("invincible",
                        new RangedContext(true, true, 10, true, true, 0, 40, 0, false, RangedMode.RANGED, 1),
                        RangedRule.TARGET_INVINCIBLE),
                new Case("too close",
                        new RangedContext(true, false, 2, true, true, 0, 40, 0, false, RangedMode.RANGED, 1),
                        RangedRule.TOO_CLOSE),
                new Case("too far",
                        new RangedContext(true, false, 99, true, true, 0, 40, 0, false, RangedMode.RANGED, 1),
                        RangedRule.TOO_FAR),
                new Case("airborne",
                        new RangedContext(true, false, 10, true, false, 0, 40, 0, false, RangedMode.RANGED, 1),
                        RangedRule.AIRBORNE),
                new Case("crowded",
                        new RangedContext(true, false, 10, true, true, 9, 40, 0, false, RangedMode.RANGED, 1),
                        RangedRule.CROWDED),
                new Case("blind",
                        new RangedContext(true, false, 10, false, true, 0, 40, 0, false, RangedMode.RANGED, 1),
                        RangedRule.NO_LINE_OF_SIGHT));

        for (Case c : cases) {
            RangedDecision d = RangedDecision.decide(c.ctx(), S);

            assertFalse(d.isRanged(), c.name() + " must drop to MELEE despite the hysteresis window");
            assertEquals(c.expected(), d.reason(), c.name());
        }
    }

    @Test
    void neverDisablesEverything() {
        RangedContext c = new RangedContext(true, false, 10.0, true, true, 0, 40, 0, true,
                RangedMode.RANGED, 1);

        RangedDecision d = RangedDecision.decide(c, S.withOverride(RangedOverride.NEVER));

        assertFalse(d.isRanged());
        assertEquals(RangedRule.DISABLED, d.reason());
    }

    @Test
    void alwaysSkipsTheRulesButNotTheGates() {
        RangedSettings always = S.withOverride(RangedOverride.ALWAYS);

        RangedContext noRule = base();
        RangedContext blind = new RangedContext(true, false, 10.0, false, true, 0, 0, 0, false,
                RangedMode.MELEE, 0);

        assertEquals(RangedRule.FORCED, RangedDecision.decide(noRule, always).reason());
        assertEquals(RangedRule.NO_LINE_OF_SIGHT, RangedDecision.decide(blind, always).reason());
    }

    @Test
    void towerQuotaIsTunable() {
        RangedContext oneTowerer = new RangedContext(true, false, 10.0, true, true, 0, 0, 1,
                false, RangedMode.MELEE, 0);

        assertFalse(RangedDecision.decide(oneTowerer, S).isRanged());
        assertTrue(RangedDecision.decide(oneTowerer, S.withTowerQuota(1)).isRanged());
    }

    @Test
    void everyRuleHasASnakeCaseLabel() {
        for (RangedRule rule : RangedRule.values()) {
            assertTrue(rule.label().matches("[a-z_]+"),
                    rule + " must have a snake_case label for /tplus info, got " + rule.label());
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew test --tests 'net.nuggetmc.tplus.bot.ranged.RangedDecisionTest'`

Expected: FAIL — compilation error, `RangedDecision` does not exist.

- [ ] **Step 5: Write `RangedDecision`**

`src/main/java/net/nuggetmc/tplus/bot/ranged/RangedDecision.java`:

```java
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
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'net.nuggetmc.tplus.bot.ranged.RangedDecisionTest'`

Expected: PASS, 19 tests.

- [ ] **Step 7: Remove the unused helper**

`RangedDecisionTest.with(...)` was scaffolding and is unused. Delete the method and the
`java.util.function.UnaryOperator` it needs. Re-run the test command above; still PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/ranged src/test/java/net/nuggetmc/tplus/bot/ranged
git commit -m "feat: the ranged decision, as a pure function

Gates first and unconditionally, rules after, hysteresis last and only over
rules. A gate failing drops a bot to MELEE on the tick it fails, because
holding RANGED through a failed gate means standing still and not shooting.
everyGateBypassesTheHysteresisWindow exists to catch that inversion.

MELEE_FLOOR is 4.0 and the comparison is strict-less, making it the exact
complement of LegacyAgent.attack's >= 4 skip. A > would leave a band at
exactly 4.0 where a bot does neither.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: `BowBallistics` — where to aim

Pure trajectory maths, independent of Task 1.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/bot/ranged/BowBallistics.java`
- Test: `src/test/java/net/nuggetmc/tplus/bot/ranged/BowBallisticsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record BowBallistics.Aim(float yaw, float pitch, int flightTicks)`
  - `static @Nullable Aim solve(Vec3 origin, Vec3 target, double power)`
  - `static @Nullable Aim solveWithLead(Vec3 origin, Vec3 target, Vec3 targetVelocity, double power)`
  - `static double heightAt(double pitchDegrees, double horizontal, double power)` — package-visible for tests
  - constants `GRAVITY = 0.05`, `DRAG = 0.99`, `LAUNCH_EYE_OFFSET = 0.1`, `FULL_DRAW_POWER = 3.0`

- [ ] **Step 1: Write the failing tests**

`src/test/java/net/nuggetmc/tplus/bot/ranged/BowBallisticsTest.java`:

```java
package net.nuggetmc.tplus.bot.ranged;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure. {@code Vec3} needs no registry, which is why {@code MotionVecTest} and
 * {@code RegionWeightTest} already use it at this tier.
 *
 * <p>Every assertion re-simulates the solved trajectory and checks where it actually arrives,
 * rather than pinning an angle. Pinning an angle would pass just as happily with the drag and
 * gravity constants swapped.
 */
class BowBallisticsTest {

    private static final double POWER = BowBallistics.FULL_DRAW_POWER;

    /** Allowed vertical miss at the target's horizontal distance. A player hitbox is 1.8 tall. */
    private static final double TOLERANCE = 0.35;

    private static void assertHits(Vec3 origin, Vec3 target) {
        BowBallistics.Aim aim = BowBallistics.solve(origin, target, POWER);
        assertNotNull(aim, "no solution for " + origin + " -> " + target);

        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double arrivedY = origin.y + BowBallistics.heightAt(aim.pitch(), horizontal, POWER);
        double miss = Math.abs(arrivedY - target.y);

        assertTrue(miss <= TOLERANCE,
                "missed by " + miss + " blocks at " + horizontal + " away; pitch " + aim.pitch());
    }

    @Test
    void aLevelTwentyBlockShotLands() {
        assertHits(new Vec3(0, 64, 0), new Vec3(20, 64, 0));
    }

    @Test
    void anUpwardShotLands() {
        assertHits(new Vec3(0, 64, 0), new Vec3(14, 72, 0));
    }

    @Test
    void aDownwardShotLands() {
        assertHits(new Vec3(0, 80, 0), new Vec3(16, 64, 0));
    }

    @Test
    void aDiagonalShotLands() {
        assertHits(new Vec3(0, 64, 0), new Vec3(9, 67, -12));
    }

    @Test
    void aShotBeyondMaxRangeStillSolves() {
        // Deliberately outside RangedSettings.DEFAULTS.maxRange(). The solver has no notion of
        // range -- that is the gate's job -- and a solver that silently refused long shots would
        // make the gate untunable.
        assertHits(new Vec3(0, 64, 0), new Vec3(40, 64, 0));
    }

    @Test
    void aimPointsAtTheTargetHorizontally() {
        BowBallistics.Aim aim = BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(0, 64, 10), POWER);
        assertNotNull(aim);

        // MC yaw: 0 is +Z, and yaw = atan2(-dx, dz). Straight down +Z is yaw 0.
        assertTrue(Math.abs(aim.yaw()) < 0.01, "expected yaw ~0 looking down +Z, got " + aim.yaw());

        BowBallistics.Aim west = BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(-10, 64, 0), POWER);
        assertNotNull(west);
        assertTrue(Math.abs(west.yaw() - 90f) < 0.01, "expected yaw ~90 looking down -X, got " + west.yaw());
    }

    @Test
    void anUnreachableTargetHasNoSolution() {
        // Straight up out of reach: an arrow at 3.0 blocks/tick under 0.05 gravity cannot climb
        // 200 blocks.
        assertNull(BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(5, 264, 0), POWER));
    }

    @Test
    void flightTimeGrowsWithDistance() {
        BowBallistics.Aim near = BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(6, 64, 0), POWER);
        BowBallistics.Aim far = BowBallistics.solve(new Vec3(0, 64, 0), new Vec3(22, 64, 0), POWER);

        assertNotNull(near);
        assertNotNull(far);
        assertTrue(far.flightTicks() > near.flightTicks(),
                "22 blocks must take longer than 6: " + far.flightTicks() + " vs " + near.flightTicks());
    }

    @Test
    void leadingAMovingTargetAimsAheadOfIt() {
        Vec3 origin = new Vec3(0, 64, 0);
        Vec3 target = new Vec3(20, 64, 0);
        Vec3 velocity = new Vec3(0, 0, 0.28); // a sprinting player, ~0.28 blocks/tick

        BowBallistics.Aim still = BowBallistics.solve(origin, target, POWER);
        BowBallistics.Aim led = BowBallistics.solveWithLead(origin, target, velocity, POWER);

        assertNotNull(still);
        assertNotNull(led);

        // The lead must swing the yaw toward +Z, which is where the target is going.
        assertTrue(led.yaw() != still.yaw(), "a moving target must change the aim");
        assertTrue(led.yaw() < still.yaw(),
                "leading a +Z-bound target must decrease yaw; " + led.yaw() + " vs " + still.yaw());
    }

    @Test
    void aStationaryTargetLeadsToTheSameAimAsNoLead() {
        Vec3 origin = new Vec3(0, 64, 0);
        Vec3 target = new Vec3(15, 64, 3);

        BowBallistics.Aim still = BowBallistics.solve(origin, target, POWER);
        BowBallistics.Aim led = BowBallistics.solveWithLead(origin, target, Vec3.ZERO, POWER);

        assertNotNull(still);
        assertNotNull(led);
        assertTrue(Math.abs(still.pitch() - led.pitch()) < 0.001f);
        assertTrue(Math.abs(still.yaw() - led.yaw()) < 0.001f);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'net.nuggetmc.tplus.bot.ranged.BowBallisticsTest'`

Expected: FAIL — compilation error, `BowBallistics` does not exist.

- [ ] **Step 3: Write `BowBallistics`**

`src/main/java/net/nuggetmc/tplus/bot/ranged/BowBallistics.java`:

```java
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
     * <p>{@code AbstractArrow(type, mob, level, …)} delegates to
     * {@code mob.getX(), mob.getEyeY() - 0.1F, mob.getZ()}. Callers must subtract this from the
     * eye before solving, or every shot carries a fixed bias -- and because this class is pure,
     * its tests would happily pin that bias forever.
     */
    public static final double LAUNCH_EYE_OFFSET = 0.1;

    /** {@code BowItem.releaseUsing} passes {@code pow * 3.0F}, and a full draw makes pow 1.0. */
    public static final double FULL_DRAW_POWER = 3.0;

    /** Generous: 24 blocks at 3 blocks/tick is ~10 ticks flat, more when lofted. */
    private static final int MAX_FLIGHT_TICKS = 120;

    /** 20 halvings of a 178-degree span converges below 0.0002 degrees. */
    private static final int SEARCH_STEPS = 20;

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

        // Height at a given pitch is monotonically decreasing in pitch -- more negative pitch
        // means more upward launch means the arrow is higher when it arrives -- so a plain
        // bisection converges. Bounds stop short of +/-90 because the horizontal velocity
        // vanishes there and the arrow never arrives.
        double lo = -89.0;
        double hi = 89.0;

        if (heightAt(lo, horizontal, power) < dy) {
            // Even launched almost straight up the arrow arrives below the target. Out of reach.
            return null;
        }

        if (heightAt(hi, horizontal, power) > dy) {
            // Even launched almost straight down it arrives above. Only reachable when the
            // target is far below and very close, which the melee floor already excludes.
            return null;
        }

        for (int i = 0; i < SEARCH_STEPS; i++) {
            double mid = (lo + hi) / 2.0;

            if (heightAt(mid, horizontal, power) > dy) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        double pitch = (lo + hi) / 2.0;
        return new Aim(yaw, (float) pitch, flightTicks(pitch, horizontal, power));
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'net.nuggetmc.tplus.bot.ranged.BowBallisticsTest'`

Expected: PASS, 10 tests.

If `anUnreachableTargetHasNoSolution` fails, the `heightAt(lo, …) < dy` guard is returning a
finite number where it should return `NEGATIVE_INFINITY` — check that `MAX_FLIGHT_TICKS` is
being hit and the loop falls through rather than returning an interpolated value.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/ranged/BowBallistics.java src/test/java/net/nuggetmc/tplus/bot/ranged/BowBallisticsTest.java
git commit -m "feat: solve a bow's launch angle by simulation

An arrow has no closed-form trajectory: 0.99 multiplicative drag and 0.05
gravity subtraction per tick. Bisect the launch angle over a simulation
instead, then one lead pass against where the target will be after the
flight time the first solve reports.

The integration order is vanilla's and is load-bearing. AbstractArrow.tick
moves, then applyInertia, then applyGravity -- so position += velocity;
velocity *= 0.99; velocity.y -= 0.05. Any other order is plausible and
consistently wrong.

LAUNCH_EYE_OFFSET is 0.1 because AbstractArrow spawns at getEyeY() - 0.1F,
not at the eye. This class is pure, so its tests would have pinned that
bias permanently had it been missed.

Tests re-simulate the solved angle and assert where the arrow arrives
rather than pinning the angle, which would pass with the constants swapped.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: The bow slot on `Bot`

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java` (field beside `defaultItem` at :83; accessors beside `setDefaultItem` at :388)
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces on `Bot`:
  - `void setBow(ItemStack item)` — a null or empty stack clears the slot
  - `ItemStack getBow()` — the slot's own contents, possibly empty
  - `boolean hasBow()` — slot non-empty, **or** `defaultItem` is a `BowItem`
  - `ItemStack bowStack()` — what to actually put in hand: the slot if set, else `defaultItem`

- [ ] **Step 1: Write the failing GameTest**

Create `src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java`:

```java
package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;

/**
 * In-world tests for the bow: the slot, the sight predicate, the draw state machine and the
 * {@code tickBot} wiring.
 *
 * <p>Bots inherit the level's default gamemode and the GameTest level is CREATIVE, which makes
 * every damage assertion vacuously true -- so every bot here is set to SURVIVAL explicitly, the
 * way {@code BotCombatTests} does.
 */
@ForEachTest(groups = BotArcheryTests.GROUP)
public final class BotArcheryTests {

    public static final String GROUP = "bot.archery";

    private BotArcheryTests() {
    }

    static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, BlockPos relative,
                     String name) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relative));

        Bot bot = BotFactory.spawn(registry, level, pos, 0f, 0f,
                BotGameProfiles.create(name, null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder("an_empty_bow_slot_means_no_bow")
    static void an_empty_bow_slot_means_no_bow(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1), "SlotBot");

        helper.assertFalse(bot.hasBow(), "a fresh bot must not have a bow");
        helper.assertTrue(bot.getBow().isEmpty(), "the slot must start empty");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder("the_bow_slot_is_independent_of_the_default_item")
    static void the_bow_slot_is_independent_of_the_default_item(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 3), "SlotBot2");

        bot.setDefaultItem(new ItemStack(Items.NETHERITE_SWORD));
        bot.setBow(new ItemStack(Items.BOW));

        helper.assertTrue(bot.hasBow(), "the slot must make hasBow true");
        helper.assertTrue(bot.bowStack().getItem() == Items.BOW, "bowStack must be the slot's bow");

        // The sword is untouched -- this is the whole point of a separate slot.
        bot.setItem(null);
        helper.assertTrue(bot.getMainHandItem().getItem() == Items.NETHERITE_SWORD,
                "setItem(null) must still restore the sword, not the bow");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder("a_bow_as_the_default_item_implies_the_slot")
    static void a_bow_as_the_default_item_implies_the_slot(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "SlotBot3");

        // `/tplus create Archer 3 none none none minecraft:bow` is what an operator types, and
        // it must arm them rather than being quietly ignored.
        bot.setDefaultItem(new ItemStack(Items.BOW));

        helper.assertTrue(bot.hasBow(), "a bow default item must imply the slot");
        helper.assertTrue(bot.bowStack().getItem() == Items.BOW, "bowStack falls back to the default item");
        helper.assertTrue(bot.getBow().isEmpty(), "but the slot itself stays empty");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder("clearing_the_bow_slot_disarms")
    static void clearing_the_bow_slot_disarms(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 7), "SlotBot4");

        bot.setBow(new ItemStack(Items.BOW));
        helper.assertTrue(bot.hasBow(), "armed");

        bot.setBow(ItemStack.EMPTY);
        helper.assertFalse(bot.hasBow(), "/tplus bow none must disarm");

        bot.setBow(new ItemStack(Items.BOW));
        bot.setBow(null);
        helper.assertFalse(bot.hasBow(), "a null stack must disarm too");

        helper.succeed();
    }
}
```

- [ ] **Step 2: Run the GameTests to verify they fail**

Run: `./gradlew runGameTestServer`

Expected: FAIL — compilation error, `Bot.setBow` does not exist.

- [ ] **Step 3: Add the field to `Bot`**

In `src/main/java/net/nuggetmc/tplus/bot/Bot.java`, immediately after the `defaultItem`
declaration (currently line 83):

```java
    /**
     * The bow a bot swaps to when {@code Archery} puts it in RANGED, kept separate from
     * {@link #defaultItem} so a bot can carry a sword and a bow at once.
     *
     * <p>Not an equipment slot in the vanilla sense -- nothing renders it while it is stowed.
     * It is where the bow lives between draws, and {@code Archery} moves it into the main hand
     * and back.
     *
     * <p>Empty is the normal state. {@link #hasBow()} also answers true when {@link #defaultItem}
     * is itself a bow, which is what makes
     * {@code /tplus create Archer 3 none none none minecraft:bow} arm a squad.
     */
    private ItemStack bowItem = ItemStack.EMPTY;
```

- [ ] **Step 4: Add the accessors to `Bot`**

Immediately after `setDefaultItem` (currently line 388-390), and add
`import net.minecraft.world.item.BowItem;` to the import block:

```java
    /** Sets the stowed bow. A null or empty stack disarms the bot. */
    public void setBow(ItemStack item) {
        this.bowItem = item == null ? ItemStack.EMPTY : item;
    }

    /** The stowed bow itself, which is empty unless one was set. */
    public ItemStack getBow() {
        return bowItem;
    }

    /**
     * Whether this bot can shoot at all.
     *
     * <p>Two ways to be armed, both of which an operator will use: an explicit bow in the slot,
     * or a default item that is itself a bow. The second costs the bot its melee damage --
     * {@code ItemUtils}' 1.8 table has no bow entry and falls through to {@code FIST = 0.25} --
     * but refusing it outright would be worse than the fist damage, because it is the obvious
     * thing to type.
     */
    public boolean hasBow() {
        return !bowItem.isEmpty() || defaultItem.getItem() instanceof BowItem;
    }

    /** What to actually put in the hand when drawing: the slot if set, otherwise the default. */
    public ItemStack bowStack() {
        return bowItem.isEmpty() ? defaultItem : bowItem;
    }
```

- [ ] **Step 5: Run the GameTests to verify they pass**

Run: `./gradlew runGameTestServer`

Expected: PASS. The four `bot.archery` tests appear in the summary. If they do not appear at
all, check every method carries `@TestHolder` — a missing one is silently unregistered and the
suite still reports success.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/Bot.java src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java
git commit -m "feat: a bow slot on Bot, separate from the default item

Separate because a bot must be able to carry a sword and a bow at once;
folding the bow into defaultItem would cost it every melee hit, since the
1.8 table in ItemUtils has no bow entry and falls through to FIST = 0.25.

hasBow() still answers true when defaultItem is itself a bow, because
'/tplus create Archer 3 none none none minecraft:bow' is what an operator
types and quietly ignoring it would be worse than the fist damage.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: `RangedSight` — a line-of-sight predicate an arrow can trust

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/RangedSight.java`
- Modify: `src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java` (append tests)

**Interfaces:**
- Consumes: `BlockRules.isAir`, `BlockRules.isWater` (existing).
- Produces:
  - `static final int SAMPLES_PER_BLOCK = 4`
  - `static boolean clear(ServerLevel level, Vec3 a, Vec3 b)`
  - `static boolean canSee(ServerLevel level, Vec3 eye, Entity target)` — the two-ray check
  - `static boolean passable(BlockState state)`

- [ ] **Step 1: Write the failing GameTests**

Append to `src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java` (and add
`import net.minecraft.world.level.block.Blocks;` and
`import net.nuggetmc.tplus.agent.legacy.RangedSight;` to its imports):

```java
    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("open_air_is_a_clear_shot")
    static void open_air_is_a_clear_shot(ExtendedGameTestHelper helper) {
        Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 2)));
        Vec3 to = Vec3.atCenterOf(helper.absolutePos(new BlockPos(13, 2, 2)));

        helper.assertTrue(RangedSight.clear(helper.getLevel(), from, to),
                "12 blocks of air must be a clear shot");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("a_wall_blocks_the_shot")
    static void a_wall_blocks_the_shot(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 2, 2), Blocks.STONE);

        Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 2)));
        Vec3 to = Vec3.atCenterOf(helper.absolutePos(new BlockPos(13, 2, 2)));

        helper.assertFalse(RangedSight.clear(helper.getLevel(), from, to),
                "a stone block on the line must block the shot");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("water_blocks_a_shot_the_melee_check_would_allow")
    static void water_blocks_a_shot_the_melee_check_would_allow(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 2, 2), Blocks.WATER);

        Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 2)));
        Vec3 to = Vec3.atCenterOf(helper.absolutePos(new BlockPos(13, 2, 2)));

        // BlockRules.AIR contains WATER, because it is a movement predicate and a bot can swim.
        // An arrow cannot: crossing water drops it to WATER_INERTIA = 0.6 and it falls short.
        // This test is what stops someone "tidying" RangedSight back into checkFreeSpace.
        helper.assertTrue(
                net.nuggetmc.tplus.agent.legacy.LegacyUtils.checkFreeSpace(helper.getLevel(), from, to),
                "precondition: the melee check must call this line clear");
        helper.assertFalse(RangedSight.clear(helper.getLevel(), from, to),
                "the ranged check must treat water as blocking");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("lava_blocks_the_shot")
    static void lava_blocks_the_shot(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 2, 2), Blocks.LAVA);

        Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 2)));
        Vec3 to = Vec3.atCenterOf(helper.absolutePos(new BlockPos(13, 2, 2)));

        helper.assertFalse(RangedSight.clear(helper.getLevel(), from, to),
                "an arrow through lava catches fire; treat it as blocking");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("grass_does_not_block_the_shot")
    static void grass_does_not_block_the_shot(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 2, 2), Blocks.SHORT_GRASS);

        Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 2)));
        Vec3 to = Vec3.atCenterOf(helper.absolutePos(new BlockPos(13, 2, 2)));

        // The vegetation exemptions are kept from BlockRules.AIR. Only water and lava change.
        helper.assertTrue(RangedSight.clear(helper.getLevel(), from, to),
                "an arrow passes through grass");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("a_quarter_block_step_still_catches_a_full_block")
    static void a_quarter_block_step_still_catches_a_full_block(ExtendedGameTestHelper helper) {
        // RangedSight samples 4 points per block instead of checkFreeSpace's 32, which is what
        // makes a 24-block ray affordable. A 0.25 step cannot pass through a 1.0-wide block, and
        // this pins that at the far end of the range where the step count is largest.
        helper.setBlock(new BlockPos(12, 2, 2), Blocks.OBSIDIAN);

        Vec3 from = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 2)));
        Vec3 to = Vec3.atCenterOf(helper.absolutePos(new BlockPos(13, 2, 2)));

        helper.assertFalse(RangedSight.clear(helper.getLevel(), from, to),
                "a coarser sample must still catch a full block");

        helper.succeed();
    }
```

- [ ] **Step 2: Run the GameTests to verify they fail**

Run: `./gradlew runGameTestServer`

Expected: FAIL — compilation error, `RangedSight` does not exist.

- [ ] **Step 3: Write `RangedSight`**

`src/main/java/net/nuggetmc/tplus/agent/legacy/RangedSight.java`:

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Whether an arrow has a clear line to something.
 *
 * <p>Deliberately <b>not</b> {@link LegacyUtils#checkFreeSpace}, which is the melee check, for
 * two independent reasons. Same two-ray shape; different constants and a different notion of
 * empty. Registered as part of deviation 38.
 *
 * <p><b>It is priced differently.</b> {@code checkFreeSpace} samples 32 points per block of
 * distance. That is affordable for melee because the same 4-block gate that calls it bounds the
 * ray to 128 lookups, and because it only runs on {@code tickDelay(3)}. A 24-block ray at the
 * same density is 768 lookups, doubled when the first ray fails, per bot, per tick. Four samples
 * per block is 96, and a 0.25-block step still cannot pass through a 1.0-wide block -- only
 * sub-block geometry reads differently, and {@code checkFreeSpace} already documents that it is
 * not a raycast and misses thin diagonal gaps anyway.
 *
 * <p><b>It disagrees about what is empty.</b> {@code BlockRules.isAir} is a set-membership test
 * whose set contains {@code WATER}, {@code LAVA}, {@code FIRE}, {@code SNOW} and several plants.
 * That is right for its actual job -- it is a movement predicate, and a bot can walk or swim
 * through all of those. It is wrong for a projectile: an arrow crossing water drops to
 * {@code AbstractArrow.WATER_INERTIA = 0.6} and falls short, and one crossing lava catches fire.
 * So water and lava block here while the vegetation exemptions are kept.
 *
 * <p>Upstream had no ranged combat, so none of this is a translation.
 */
public final class RangedSight {

    /**
     * Samples per block of distance. {@code checkFreeSpace} uses 32; see the class comment for
     * why this one cannot afford that and why 4 is still sufficient for full blocks.
     */
    public static final int SAMPLES_PER_BLOCK = 4;

    private RangedSight() {
    }

    /** Whether an arrow can fly through this block. */
    public static boolean passable(BlockState state) {
        return BlockRules.isAir(state) && !BlockRules.isWater(state) && !isLava(state);
    }

    private static boolean isLava(BlockState state) {
        return state.getBlock() == net.minecraft.world.level.block.Blocks.LAVA;
    }

    /**
     * Whether the straight line from {@code a} to {@code b} is passable for an arrow.
     *
     * <p>Two identical points are trivially clear, matching {@code checkFreeSpace}'s explicit
     * zero case -- which exists there because upstream divided by an unchecked length and relied
     * on every NaN comparison being false.
     */
    public static boolean clear(ServerLevel level, Vec3 a, Vec3 b) {
        Vec3 v = b.subtract(a);
        double length = v.length();

        if (length == 0) {
            return true;
        }

        int steps = (int) Math.floor(length * SAMPLES_PER_BLOCK);
        Vec3 step = v.scale(1.0 / (length * SAMPLES_PER_BLOCK));

        for (int i = 0; i <= steps; i++) {
            BlockPos pos = BlockPos.containing(a.add(step.scale(i)));

            if (!passable(level.getBlockState(pos))) {
                return false;
            }
        }

        return true;
    }

    /**
     * The two-ray check, mirroring what {@code tickBot} does before a melee swing: eye-to-eye or
     * eye-to-feet, because a target behind a half-height wall is still shootable over it.
     */
    public static boolean canSee(ServerLevel level, Vec3 eye, Entity target) {
        return clear(level, eye, target.getEyePosition()) || clear(level, eye, target.position());
    }
}
```

- [ ] **Step 4: Run the GameTests to verify they pass**

Run: `./gradlew runGameTestServer`

Expected: PASS. Ten `bot.archery` tests now.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy/RangedSight.java src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java
git commit -m "feat: a line-of-sight predicate an arrow can trust

checkFreeSpace cannot be reused for this, for two independent reasons.

It is priced for four blocks: 32 samples per block of distance, affordable
only because the melee gate that calls it bounds the ray to 128 lookups and
only on tickDelay(3). A 24-block ray at that density is 768 per ray per bot
per tick. Four per block is 96, and a 0.25 step still cannot pass through a
1.0-wide block.

And it disagrees with a projectile about what is empty: BlockRules.AIR is a
set containing WATER and LAVA, correct for a movement predicate since a bot
can swim, wrong for an arrow, which drops to 0.6 inertia in water. Water and
lava block here; the vegetation exemptions are kept.

water_blocks_a_shot_the_melee_check_would_allow asserts both halves, so the
divergence cannot be quietly tidied away.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: `Archery` — the draw state machine

The collaborator, built and tested by direct call. No `tickBot` wiring yet; that is Task 6.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/Archery.java`
- Modify: `src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java` (append tests)

**Interfaces:**
- Consumes: `RangedContext`, `RangedDecision`, `RangedSettings`, `RangedMode`, `RangedRule`, `RangedOverride` (Task 1); `BowBallistics` (Task 2); `Bot.hasBow/bowStack` (Task 3); `RangedSight.canSee` (Task 4); `AgentState`, `Agent`, `Mining` (existing).
- Produces:
  - `Archery(AgentState state, Agent agent, Mining mining)`
  - `boolean tick(Bot bot, Entity target)` — true means "handled, suppress movement"
  - `void reset(Bot bot)` — drop any draw, restore the hand
  - `void forget(Bot bot)` — drop all per-bot state
  - `void clear()` — drop every bot's state
  - `RangedSettings settings()` / `void setSettings(RangedSettings)`
  - `@Nullable RangedDecision lastDecision(Bot bot)`
  - `boolean isDrawing(Bot bot)`

- [ ] **Step 1: Write `Archery`**

This task writes the implementation before the tests, because the state machine's public surface
is what the tests drive and there is no smaller increment that compiles. The tests in Step 2 are
still written against the spec, not against the code, and Step 3 runs them.

`src/main/java/net/nuggetmc/tplus/agent/legacy/Archery.java`:

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.ranged.BowBallistics;
import net.nuggetmc.tplus.bot.ranged.RangedContext;
import net.nuggetmc.tplus.bot.ranged.RangedDecision;
import net.nuggetmc.tplus.bot.ranged.RangedMode;
import net.nuggetmc.tplus.bot.ranged.RangedSettings;
import net.nuggetmc.tplus.util.PlayerUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The bow. Samples the world into a {@link RangedContext}, asks {@link RangedDecision}, and runs
 * a per-bot draw state machine when the answer is RANGED.
 *
 * <p>Upstream had no ranged combat, so none of this is a translation. It sits beside
 * {@code Navigation} and {@code Mining} and takes the same injected {@code AgentState}.
 *
 * <p><b>The draw is ticked here rather than scheduled.</b> The shield schedules its release with
 * {@code scheduler.runLater}; a bow cannot, because a scheduled release cannot re-aim and a Ghast
 * drifts. Ticking also means a bot that dies or is removed simply stops, with no task to cancel.
 *
 * <p><b>Firing bypasses {@code BowItem.releaseUsing} entirely.</b> That method's first step reads
 * {@code useItemRemaining}, which never decrements for a bot -- {@code Bot.isBotBlocking}
 * documents the whole chain -- but it reads it only to recover how long the draw has been held,
 * which this class already knows because it started the draw. The arrow is spawned directly, the
 * way {@code Projectile.spawnProjectileFromRotation} does. <b>This does not deliver the generic
 * use-tick</b>: food, potions and the shield are exactly as blocked as before. Deviation 41.
 */
public final class Archery {

    /** {@code BowItem.MAX_DRAW_DURATION}: a full draw, so power is 1.0 and the arrow is critical. */
    public static final int DRAW_TICKS = 20;

    /** Ticks after a release before the next draw. 20 + 10 is about a player's sustained rate. */
    public static final int RELEASE_COOLDOWN = 10;

    /** How often the line-of-sight check is re-evaluated. Matches melee's {@code tickDelay(3)}. */
    public static final int SIGHT_PERIOD = 3;

    /** {@code BowItem.releaseUsing} passes {@code pow * 3.0F}; a full draw makes pow 1.0. */
    private static final float LAUNCH_POWER = (float) BowBallistics.FULL_DRAW_POWER;

    private enum Phase {
        IDLE,
        DRAWING,
        COOLDOWN
    }

    /** Everything this class remembers about one bot. Not in {@code AgentState} -- deviation 43. */
    private static final class State {
        RangedMode mode = RangedMode.MELEE;
        int ticksInMode;

        Phase phase = Phase.IDLE;
        int phaseTicks;

        @Nullable UUID targetId;
        int aloftTicks;

        boolean sight;
        int sightAge = Integer.MAX_VALUE;

        @Nullable RangedDecision lastDecision;
    }

    private final AgentState state;
    private final Agent agent;
    private final Mining mining;

    private final Map<Bot, State> bots = new HashMap<>();

    private RangedSettings settings = RangedSettings.DEFAULTS;

    public Archery(AgentState state, Agent agent, Mining mining) {
        this.state = state;
        this.agent = agent;
        this.mining = mining;
    }

    public RangedSettings settings() {
        return settings;
    }

    public void setSettings(RangedSettings value) {
        this.settings = value;
    }

    public @Nullable RangedDecision lastDecision(Bot bot) {
        State s = bots.get(bot);
        return s == null ? null : s.lastDecision;
    }

    public boolean isDrawing(Bot bot) {
        State s = bots.get(bot);
        return s != null && s.phase == Phase.DRAWING;
    }

    /**
     * One tick of ranged behaviour.
     *
     * @return true when the bot is holding position to shoot, which {@code tickBot} reads as
     *         "handled, stop here" and is the whole of the hold-position decision
     */
    public boolean tick(Bot bot, Entity target) {
        State s = bots.computeIfAbsent(bot, b -> new State());

        // A bot mid-boat-crossing owns its own hand. resetHand has the same early return, whose
        // comment reads "leaves the boat in its hand"; without this a bot swaps the boat for a
        // bow halfway across a lava lake.
        if (state.boatCooldown.contains(bot)) {
            return false;
        }

        trackAloft(s, target);

        RangedContext ctx = sample(bot, target, s);
        RangedDecision decision = RangedDecision.decide(ctx, settings);
        s.lastDecision = decision;

        if (decision.mode() != s.mode) {
            s.mode = decision.mode();
            s.ticksInMode = 0;
        } else {
            s.ticksInMode++;
        }

        if (!decision.isRanged()) {
            if (s.phase != Phase.IDLE) {
                stopDraw(bot, s);
            }

            return false;
        }

        // BOT_STUCK fires precisely when a bot is mining and getting nowhere, so a mining bot
        // entering RANGED is the common path rather than an edge case. Navigation.tower and
        // resetHand both call this before taking the hand; so does this.
        mining.stopMining(bot);

        advance(bot, target, s);
        return true;
    }

    /** Drops any draw and gives the hand back. Safe on a bot this class has never seen. */
    public void reset(Bot bot) {
        State s = bots.get(bot);

        if (s == null) {
            return;
        }

        if (s.phase != Phase.IDLE) {
            stopDraw(bot, s);
        }

        s.mode = RangedMode.MELEE;
        s.ticksInMode = 0;
        s.aloftTicks = 0;
        s.targetId = null;
        s.sightAge = Integer.MAX_VALUE;
    }

    /** Forgets a bot entirely. Called from {@code BotRegistry.remove}. */
    public void forget(Bot bot) {
        bots.remove(bot);
    }

    /** Forgets every bot. Called from {@code LegacyAgent.stopAllTasks}. */
    public void clear() {
        bots.clear();
    }

    // ---- the state machine ------------------------------------------------

    private void advance(Bot bot, Entity target, State s) {
        switch (s.phase) {
            case IDLE -> startDraw(bot, s);

            case DRAWING -> {
                s.phaseTicks++;
                aim(bot, target);

                if (s.phaseTicks >= DRAW_TICKS) {
                    release(bot, target, s);
                }
            }

            case COOLDOWN -> {
                s.phaseTicks++;
                aim(bot, target);

                if (s.phaseTicks >= RELEASE_COOLDOWN) {
                    startDraw(bot, s);
                }
            }
        }
    }

    private void startDraw(Bot bot, State s) {
        s.phase = Phase.DRAWING;
        s.phaseTicks = 0;

        bot.setItem(bot.bowStack().copy());

        // The client renders another player's bow pull from the living-entity-flags byte and
        // counts the draw ticks itself, so this pair is the whole animation -- the same pair the
        // shield path sends. The server-side useItemRemaining counter stays frozen at zero and
        // nothing here reads it.
        bot.startUsingItem(InteractionHand.MAIN_HAND);
        bot.broadcastEntityData();
    }

    private void stopDraw(Bot bot, State s) {
        s.phase = Phase.IDLE;
        s.phaseTicks = 0;

        bot.stopUsingItem();
        bot.broadcastEntityData();

        // null means "restore the default item", not "empty".
        bot.setItem(null);
    }

    private void release(Bot bot, Entity target, State s) {
        ServerLevel level = (ServerLevel) bot.level();

        // The firing line gates the shot, not the mode: a squadmate wandering through is
        // transient, and flipping mode over it would flicker the bot in and out of navigation.
        // So a blocked line holds position, holds fire, and looses when it clears.
        if (canFire(bot, target, level)) {
            shoot(bot, target, level);
        }

        s.phase = Phase.COOLDOWN;
        s.phaseTicks = 0;

        bot.stopUsingItem();
        bot.broadcastEntityData();
    }

    private boolean canFire(Bot bot, Entity target, ServerLevel level) {
        Vec3 eye = bot.getEyePosition();

        if (!RangedSight.canSee(level, eye, target)) {
            return false;
        }

        // Friendly fire is left on deliberately -- bot arrows are owned by a Player and gated by
        // canHarmPlayer, which says yes by default. This only declines shots a squadmate would
        // physically swallow, so a draw is not wasted on one.
        Vec3 aimPoint = aimPoint(target);

        for (Bot other : agentBots()) {
            if (other == bot || !other.isBotAlive()) {
                continue;
            }

            if (segmentHitsBot(eye, aimPoint, other)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Whether {@code other}'s hitbox lies on the segment from {@code from} to {@code to}.
     *
     * <p>An approximation, and knowingly so: the arrow arcs and this is a straight line. It is a
     * shot filter rather than a correctness requirement, and the crowding gate is what actually
     * keeps archers out of each other's backs.
     */
    private static boolean segmentHitsBot(Vec3 from, Vec3 to, Bot other) {
        return other.getBoundingBox().inflate(0.3).clip(from, to).isPresent();
    }

    private void shoot(Bot bot, Entity target, ServerLevel level) {
        ItemStack bow = bot.bowStack();

        // A fresh stack every shot: the bot's inventory is never read and useAmmo is never
        // called. Consistent with the cobblestone it towers with and the water buckets it
        // clutches with, and with tools that never lose durability. Deviation 40.
        Arrow arrow = new Arrow(level, bot, new ItemStack(Items.ARROW), bow);

        // Otherwise twenty archers carpet the ground with collectables, and each one is a
        // plausible `enemytarget generic minecraft:arrow` result.
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;

        // A full draw is a critical arrow, matching the `pow == 1.0F` argument
        // BowItem.releaseUsing passes.
        arrow.setCritArrow(true);

        arrow.shootFromRotation(bot, bot.getXRot(), bot.getYRot(), 0f, LAUNCH_POWER, 1.0f);
        level.addFreshEntity(arrow);

        bot.swing(InteractionHand.MAIN_HAND);

        level.playSound(null, bot.blockPosition(), SoundEvents.ARROW_SHOOT,
                SoundSource.PLAYERS, 1f, 1f);
    }

    // ---- aiming -----------------------------------------------------------

    private void aim(Bot bot, Entity target) {
        // The arrow is born at getEyeY() - 0.1F, not at the eye. A tenth of a block is nothing
        // in angle terms at 20 blocks, but BowBallistics is pure and solving from the wrong
        // origin biases every shot in the same direction.
        Vec3 origin = bot.getEyePosition().subtract(0, BowBallistics.LAUNCH_EYE_OFFSET, 0);

        BowBallistics.Aim aim = BowBallistics.solveWithLead(
                origin, aimPoint(target), target.getDeltaMovement(), BowBallistics.FULL_DRAW_POWER);

        if (aim == null) {
            // Out of reach at every angle. Keep facing the target so the bot does not look
            // broken, and let the next tick try again -- the target may come closer.
            bot.faceLocation(target.position());
            return;
        }

        bot.lookAt(aim.yaw(), aim.pitch());
    }

    /**
     * Where to point, which is not always the target's eyes.
     *
     * <p>The Ender Dragon is the exception. {@code Level.getEntities} merges {@code dragonParts()}
     * into every query, so an arrow hits the dragon with no special handling -- but it hits
     * whichever {@code EnderDragonPart} is geometrically in the way, and {@code EnderDragon.hurt}
     * opens with {@code if (part != this.head) damage = damage / 4 + min(damage, 1)}. Deviation 37
     * fixed that for melee by redirecting the recipient; a projectile has no recipient to
     * redirect, so the fix moves into the aim point. It does not guarantee a head hit -- a wing
     * can still intercept -- so it is an improvement in expectation.
     */
    private static Vec3 aimPoint(Entity target) {
        return target instanceof EnderDragon dragon
                ? dragon.head.position()
                : target.getEyePosition();
    }

    // ---- sampling ---------------------------------------------------------

    private void trackAloft(State s, Entity target) {
        UUID id = target.getUUID();

        // Targeting.locateTarget runs every tick and may return a different entity than it did
        // last tick. Carrying the count across a switch would let a grounded target inherit a
        // Phantom's 40 ticks and flip the bot to RANGED against something standing on the floor.
        if (!id.equals(s.targetId)) {
            s.targetId = id;
            s.aloftTicks = 0;
        }

        s.aloftTicks = target.onGround() ? 0 : s.aloftTicks + 1;
    }

    private RangedContext sample(Bot bot, Entity target, State s) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        Vec3 targetPos = target.position();

        boolean invincible = target instanceof ServerPlayer player
                && PlayerUtils.isInvincible(player.gameMode());

        double distance = pos.distanceTo(targetPos);

        return new RangedContext(
                bot.hasBow(),
                invincible,
                distance,
                lineOfSight(bot, target, level, s),
                bot.isBotOnGround(),
                countNearbyBots(bot, pos),
                s.aloftTicks,
                countTowerersNear(targetPos),
                Boolean.TRUE.equals(state.btCheck.get(bot)),
                s.mode,
                s.ticksInMode);
    }

    /** Re-evaluated every {@link #SIGHT_PERIOD} ticks and cached between; see {@code RangedSight}. */
    private boolean lineOfSight(Bot bot, Entity target, ServerLevel level, State s) {
        if (s.sightAge < SIGHT_PERIOD) {
            s.sightAge++;
            return s.sight;
        }

        s.sight = RangedSight.canSee(level, bot.getEyePosition(), target);
        s.sightAge = 1;
        return s.sight;
    }

    private int countNearbyBots(Bot bot, Vec3 pos) {
        double radiusSqr = settings.crowdRadius() * settings.crowdRadius();
        int count = 0;

        for (Bot other : agentBots()) {
            if (other != bot && other.isBotAlive() && other.position().distanceToSqr(pos) <= radiusSqr) {
                count++;
            }
        }

        return count;
    }

    /** {@code state.towerList} is already "which bots are towering", so this is a filter. */
    private int countTowerersNear(Vec3 targetPos) {
        double radiusSqr = settings.towerQuotaRadius() * settings.towerQuotaRadius();
        int count = 0;

        for (Bot towerer : state.towerList.keySet()) {
            if (towerer.isBotAlive() && towerer.position().distanceToSqr(targetPos) <= radiusSqr) {
                count++;
            }
        }

        return count;
    }

    private Iterable<Bot> agentBots() {
        return agent.registryBots();
    }
}
```

- [ ] **Step 2: Add the four helpers `Archery` needs**

`Archery` calls four methods that do not exist yet — two on `Agent`, two on `Bot`. Add them.

In `src/main/java/net/nuggetmc/tplus/agent/Agent.java`, beside the other protected accessors:

```java
    /** Every live bot, or nothing when this agent has no registry (unit tests, {@code noop}). */
    public Iterable<Bot> registryBots() {
        return registry == null ? java.util.List.of() : registry.bots();
    }

    /**
     * Forgets one bot's agent-held state. Called from {@code BotRegistry.remove} beside
     * {@code AgentState.forget}, for state that deliberately does not live in {@code AgentState}.
     */
    public void forgetBot(Bot bot) {
    }
```

In `src/main/java/net/nuggetmc/tplus/bot/Bot.java`, beside `look` and `faceLocation`:

```java
    /**
     * Points the bot at an explicit yaw and pitch, and tells clients about the yaw.
     *
     * <p>{@link #faceLocation(Vec3)} aims straight at a point, which is right for a melee swing
     * and wrong for a bow: an arrow drops, so the pitch comes from a ballistic solve rather than
     * from the direction to the target. Same packet, different source for the numbers.
     */
    public void lookAt(float yaw, float pitch) {
        BotFactory.broadcast(this, new ClientboundRotateHeadPacket(this, (byte) (yaw * 256 / 360f)));
        setRot(yaw, pitch);
    }

    /** Pushes this bot's dirty entity data to clients, as the shield path does around blocking. */
    public void broadcastEntityData() {
        BotFactory.broadcast(this, new ClientboundSetEntityDataPacket(getId(), getEntityData().packDirty()));
    }
```

- [ ] **Step 3: Write the failing GameTests**

Append to `src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java`, adding imports
for `net.nuggetmc.tplus.agent.legacy.Archery`, `net.nuggetmc.tplus.agent.legacy.LegacyAgent`,
`net.nuggetmc.tplus.bot.ranged.RangedRule` and `net.nuggetmc.tplus.bot.ranged.RangedSettings`:

```java
    /**
     * Ticks every bot's physics, with no agent.
     *
     * <p><b>This is how a bot becomes grounded, and there is no shortcut.</b>
     * {@code Bot.isBotOnGround()} reads {@code groundTicks}, a counter {@code Bot.tickInternal}
     * raises only while {@code checkGround()} is true -- it does <i>not</i> read
     * {@code Entity.onGround}, so {@code setOnGround(true)} does nothing for it. A freshly
     * spawned bot has {@code groundTicks == 0} and fails the ranged {@code AIRBORNE} gate until
     * this has run. {@code AgentTests} carries the same helper for the same reason.
     */
    private static void settle(BotRegistry registry, int ticks) {
        for (int i = 0; i < ticks; i++) {
            for (Bot bot : registry.bots()) {
                bot.tick();
            }
        }
    }

    /**
     * A bot high in the air that never lands, so {@code target_flying} fires.
     *
     * <p>Held aloft by re-snapping it every tick rather than by giving it upward velocity: a bot
     * with real velocity drifts, and the test would be measuring the drift rather than the rule.
     * It is deliberately never ticked, so nothing pulls it down between snaps.
     *
     * <p>{@code setOnGround(false)} is correct <i>here</i> and wrong for the shooter: the aloft
     * counter reads {@code target.onGround()}, which is the {@code Entity} field this sets,
     * whereas the shooter's gate reads {@code Bot.groundTicks}, which only ticking raises.
     */
    private static Bot aloftTarget(ExtendedGameTestHelper helper, BotRegistry registry,
                                   BlockPos relative, String name) {
        Bot bot = spawn(helper, registry, relative, name);
        bot.setOnGround(false);
        return bot;
    }

    private static void holdAloft(Bot bot, Vec3 at) {
        bot.snapTo(at.x, at.y, at.z, bot.getYRot(), bot.getXRot());
        bot.setOnGround(false);
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_bot_draws_and_fires_at_a_flying_target")
    static void a_bot_draws_and_fires_at_a_flying_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer1");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer1");

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking
            // raises. Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            // 40 ticks of aloft to satisfy the rule, then a full 20-tick draw, then the release.
            int arrows = 0;

            for (int i = 0; i < 90; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                archery.tick(shooter, target);

                arrows = helper.getLevel()
                        .getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.Arrow.class,
                                shooter.getBoundingBox().inflate(40))
                        .size();

                if (arrows > 0) {
                    break;
                }
            }

            helper.assertTrue(arrows > 0,
                    "a bot must fire at a flying target; last decision was "
                            + archery.lastDecision(shooter));

            helper.succeed();
        } finally {
            // GameTests share a JVM and this agent holds per-bot state; leaving entries behind
            // changes what later tests decide. Same trap BlockRules' static override set has.
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_bot_inside_melee_range_never_draws")
    static void a_bot_inside_melee_range_never_draws(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(6, 1, 7), "Archer2");
            shooter.setBow(new ItemStack(Items.BOW));

            // Two blocks away and aloft: the rule fires, the gate refuses.
            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(8, 2, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(8, 2, 7), "Flyer2");

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking
            // raises. Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 80; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                helper.assertFalse(archery.tick(shooter, target),
                        "a bot inside 4 blocks must leave the tick unhandled so melee runs");
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.TOO_CLOSE,
                    "expected TOO_CLOSE, got " + archery.lastDecision(shooter));
            helper.assertFalse(archery.isDrawing(shooter), "and it must not be mid-draw");

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_wall_stops_a_bot_drawing")
    static void a_wall_stops_a_bot_drawing(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer3");
            shooter.setBow(new ItemStack(Items.BOW));

            for (int y = 1; y <= 10; y++) {
                helper.setBlock(new BlockPos(7, y, 7), Blocks.OBSIDIAN);
            }

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer3");

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking
            // raises. Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 80; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                archery.tick(shooter, target);
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.NO_LINE_OF_SIGHT,
                    "expected NO_LINE_OF_SIGHT, got " + archery.lastDecision(shooter));
            helper.assertFalse(archery.isDrawing(shooter), "and it must not be mid-draw");

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_crowded_bot_falls_through_to_navigation")
    static void a_crowded_bot_falls_through_to_navigation(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer4");
            shooter.setBow(new ItemStack(Items.BOW));

            // Four squadmates on top of it. CROWD_LIMIT defaults to 4 within 4 blocks.
            for (int i = 0; i < 4; i++) {
                spawn(helper, registry, new BlockPos(2, 1, 7), "Crowd" + i);
            }

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer4");

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking
            // raises. Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 80; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                helper.assertFalse(archery.tick(shooter, target),
                        "a crowded bot must leave the tick unhandled so it can push forward");
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.CROWDED,
                    "expected CROWDED, got " + archery.lastDecision(shooter));

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("towering_squadmates_make_the_next_bot_shoot")
    static void towering_squadmates_make_the_next_bot_shoot(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer5");
            shooter.setBow(new ItemStack(Items.BOW));

            // A grounded target, so target_flying cannot be what fires.
            Bot target = spawn(helper, registry, new BlockPos(12, 1, 7), "Ground5");

            // Three squadmates in towerList, near the target. Default quota is 3.
            for (int i = 0; i < 3; i++) {
                Bot towerer = spawn(helper, registry, new BlockPos(11, 1, 7), "Tower" + i);
                registry.state().towerList.put(towerer, towerer.position());
            }

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking
            // raises. Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 10; i++) {
                shooter.tick();
                target.tick();
                archery.tick(shooter, target);
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.TOWER_QUOTA,
                    "expected TOWER_QUOTA, got " + archery.lastDecision(shooter));

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("entering_ranged_stops_a_mining_animation")
    static void entering_ranged_stops_a_mining_animation(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer6");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer6");

            // Stand in for a running swing animation. BOT_STUCK fires precisely when a bot is
            // mining and getting nowhere, so a mining bot entering RANGED is the common path.
            registry.state().miningAnim.put(shooter, 12345);

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking
            // raises. Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 60; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                archery.tick(shooter, target);
            }

            helper.assertFalse(registry.state().miningAnim.containsKey(shooter),
                    "entering RANGED must stop the mining animation, as tower() and resetHand do");

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_bot_on_boat_cooldown_keeps_the_boat")
    static void a_bot_on_boat_cooldown_keeps_the_boat(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer7");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer7");

            // boatOverLava runs in miscellaneousChecks, ABOVE the ranged branch, and leaves a
            // boat in the hand. resetHand has the same early return; without it a bot swaps the
            // boat for a bow halfway across a lava lake.
            registry.state().boatCooldown.add(shooter);
            shooter.setItem(new ItemStack(Items.OAK_BOAT));

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking
            // raises. Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 60; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                helper.assertFalse(archery.tick(shooter, target),
                        "a bot on boat cooldown must leave the tick unhandled");
            }

            helper.assertTrue(shooter.getMainHandItem().getItem() == Items.OAK_BOAT,
                    "the boat must still be in hand, got " + shooter.getMainHandItem());

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("resetting_a_drawing_bot_restores_its_hand")
    static void resetting_a_drawing_bot_restores_its_hand(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer8");
            shooter.setDefaultItem(new ItemStack(Items.NETHERITE_SWORD));
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer8");

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking
            // raises. Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 45; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                archery.tick(shooter, target);
            }

            helper.assertTrue(archery.isDrawing(shooter), "precondition: the bot must be drawing");
            helper.assertTrue(shooter.getMainHandItem().getItem() == Items.BOW,
                    "precondition: the bow must be in hand");

            archery.reset(shooter);

            helper.assertFalse(archery.isDrawing(shooter), "reset must drop the draw");
            helper.assertTrue(shooter.getMainHandItem().getItem() == Items.NETHERITE_SWORD,
                    "reset must restore the default item, got " + shooter.getMainHandItem());

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 300)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_new_target_does_not_inherit_the_old_aloft_count")
    static void a_new_target_does_not_inherit_the_old_aloft_count(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer9");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot flyer = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer9");
            Bot walker = spawn(helper, registry, new BlockPos(12, 1, 7), "Walker9");

            settle(registry, 10);

            Archery archery = agent.archery();

            // Build up well past the 40-tick threshold against the flyer.
            for (int i = 0; i < 60; i++) {
                holdAloft(flyer, aloft);
                shooter.tick();
                archery.tick(shooter, flyer);
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.TARGET_FLYING,
                    "precondition: expected TARGET_FLYING, got " + archery.lastDecision(shooter));

            // Targeting.locateTarget runs every tick and may hand back a different entity. If the
            // count carried across the switch, this grounded bot would inherit 60 ticks of
            // "aloft" and be shot at while standing on the floor.
            for (int i = 0; i < 5; i++) {
                walker.tick();
                shooter.tick();
                archery.tick(shooter, walker);
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() != RangedRule.TARGET_FLYING,
                    "a grounded target must not inherit the flyer's aloft count; got "
                            + archery.lastDecision(shooter));

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }
```

- [ ] **Step 4: Wire `Archery` into `LegacyAgent`'s construction only**

The tests above call `agent.archery()`. Add the field and the accessor to
`src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` — construction and accessor
only. The `tickBot` branch is Task 6.

In the field block beside `navigation` and `behaviors`:

```java
    private final Archery archery;
```

In the constructor, after `this.behaviors = ...` and before `this.navigation = ...`:

```java
        // After Mining, which it stops before taking the hand, and before Navigation, which it
        // has no relationship with -- the hold-position decision is expressed by tickBot's
        // ordering rather than by a call between the two.
        this.archery = new Archery(state, this, mining);
```

Beside `targeting()`:

```java
    public Archery archery() {
        return archery;
    }
```

And in `stopAllTasks`, after `state.mining.clear();`:

```java
        archery.clear();
```

Add `forgetBot` beside `stopAllTasks`:

```java
    @Override
    public void forgetBot(Bot bot) {
        archery.forget(bot);
    }
```

- [ ] **Step 5: Call `forgetBot` from the registry**

In `src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java`, in `remove`, immediately after
`state.forget(bot);`:

```java
        // Agent-held per-bot state, for collaborators that deliberately do not keep theirs in
        // AgentState. Archery is the first; the backlog asks for that narrowing and new state is
        // the easy case, since nothing outside the owner reads it.
        agent.forgetBot(bot);
```

- [ ] **Step 6: Run the GameTests**

Run: `./gradlew runGameTestServer`

Expected: PASS. Nineteen `bot.archery` tests.

If `a_bot_draws_and_fires_at_a_flying_target` times out, print `archery.lastDecision(shooter)` —
the assertion already does. A `TOO_FAR` means the template is bigger than `maxRange` of 24; a
`NO_RULE` means `holdAloft` is not keeping `onGround` false.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy/Archery.java src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java src/main/java/net/nuggetmc/tplus/agent/Agent.java src/main/java/net/nuggetmc/tplus/bot/Bot.java src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java
git commit -m "feat: Archery, the draw state machine

Ticked rather than scheduled: the shield schedules its release, but a bow
cannot, because a scheduled release cannot re-aim and a Ghast drifts.
Ticking also means a dead or removed bot simply stops, with no task to
cancel.

Firing bypasses BowItem.releaseUsing. Its first step reads useItemRemaining,
which never decrements for a bot, but reads it only to recover how long the
draw has been held -- which this class knows, because it started the draw.
This does NOT deliver the generic use-tick; food, potions and the shield are
exactly as blocked as before.

Three interactions with code above the branch, all of which would have been
bugs. boatCooldown is honoured the way resetHand honours it, or a bot swaps
its boat for a bow mid-lava-crossing. stopMining is called on entering
RANGED, because BOT_STUCK fires precisely when a bot is mining and getting
nowhere. And the aloft counter resets on target change, or a grounded target
inherits a Phantom's 40 ticks.

State lives here rather than in AgentState -- the backlog asks for that
narrowing, and new state is the easy case since nothing outside the owner
reads it. BotRegistry.remove calls forgetBot beside state.forget.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Wire into `tickBot` — the two touch points

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` (`tickBot`, around :138 and :155)
- Modify: `src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java` (append tests)

**Interfaces:**
- Consumes: `Archery.tick`, `Archery.reset` (Task 5).
- Produces: nothing new. `tickBot` gains a branch.

- [ ] **Step 1: Write the failing GameTests**

Append to `src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java`:

```java
    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_ranged_bot_holds_its_ground")
    static void a_ranged_bot_holds_its_ground(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Holder");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "HeldFlyer");

            settle(registry, 10);

            // Warm up past the aloft threshold so the bot is actually in RANGED before the
            // measurement starts.
            for (int i = 0; i < 50; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                agent.tickBot(shooter);
            }

            helper.assertTrue(agent.archery().lastDecision(shooter).isRanged(),
                    "precondition: expected RANGED, got " + agent.archery().lastDecision(shooter));

            Vec3 before = shooter.position();

            // This test deliberately ticks and waits, which the GameTest conventions warn
            // against -- move() adds Math.random() to every jump, so a position assertion after
            // 200 ticks usually measures the walk rather than the decision. The warning does not
            // apply to asserting the ABSENCE of movement: if the branch is right there is no
            // jump and no random term, and if it is wrong the bot walks off and this fails.
            for (int i = 0; i < 100; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                agent.tickBot(shooter);
            }

            double moved = shooter.position().subtract(before).horizontalDistance();

            helper.assertTrue(moved < 0.5,
                    "a RANGED bot must hold position; it moved " + moved + " blocks");

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_drawing_bot_resets_when_its_target_disappears")
    static void a_drawing_bot_resets_when_its_target_disappears(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Orphan");
            shooter.setDefaultItem(new ItemStack(Items.NETHERITE_SWORD));
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Doomed");

            settle(registry, 10);

            for (int i = 0; i < 50; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                agent.tickBot(shooter);
            }

            helper.assertTrue(agent.archery().isDrawing(shooter),
                    "precondition: the bot must be mid-draw");

            // tickBot returns above the ranged branch when the goal finds nothing, so without a
            // reset beside mining.stopMining the bot holds a drawn bow forever. Invisible to
            // every other test here, because every other test keeps its target alive.
            target.discard();
            registry.remove(target);

            for (int i = 0; i < 5; i++) {
                shooter.tick();
                agent.tickBot(shooter);
            }

            helper.assertFalse(agent.archery().isDrawing(shooter),
                    "a bot whose target vanished must drop the draw");
            helper.assertTrue(shooter.getMainHandItem().getItem() == Items.NETHERITE_SWORD,
                    "and get its sword back, got " + shooter.getMainHandItem());

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }
```

- [ ] **Step 2: Run the GameTests to verify they fail**

Run: `./gradlew runGameTestServer`

Expected: FAIL. `a_ranged_bot_holds_its_ground` fails because nothing suppresses navigation;
`a_drawing_bot_resets_when_its_target_disappears` fails because nothing resets.

- [ ] **Step 3: Add the reset on the no-target path**

In `tickBot`, change the no-target early return (currently lines 138-141):

```java
        if (livingTarget == null) {
            mining.stopMining(bot);

            // Beside stopMining, and for the same reason it is there. This return sits ABOVE the
            // ranged branch, so a bot that is mid-draw when its target dies, teleports away or
            // is filtered out by the goal would otherwise never reach archery.tick again -- it
            // would hold a drawn bow, in a phase that never advances, indefinitely.
            archery.reset(bot);
            return;
        }
```

- [ ] **Step 4: Add the ranged branch**

In `tickBot`, immediately after the melee attack block — that is, after the closing brace of
`if (bot.tickDelay(3) && !state.miningAnim.containsKey(bot)) { ... }` and before the
`boolean waterGround = ...` line:

```java
        // Everything above this point is safety or the existing melee path; everything below it
        // is movement. So returning here IS the hold-position decision -- it is expressed as
        // ordering rather than as a flag, which is the shape the rest of this method already has.
        //
        // It also settles hand contention for free: setItem(null), which restores the default
        // item, is only called from Navigation.move and BotBehaviors.resetHand, and both sit
        // below this return. While a bot is RANGED nothing fights the bow out of its hand, and
        // the first resetHand after flipping back to MELEE restores the sword by itself.
        //
        // Deviation 38.
        if (archery.tick(bot, livingTarget)) {
            return;
        }
```

- [ ] **Step 5: Run the GameTests to verify they pass**

Run: `./gradlew runGameTestServer`

Expected: PASS. Twenty-one `bot.archery` tests, and every pre-existing test still green — in
particular the `bot.combat` and `agent` groups, which exercise `tickBot`'s ordering.

- [ ] **Step 6: Run the whole build**

Run: `./gradlew build`

Expected: PASS — 109 existing unit tests plus 29 new ones, and a clean compile.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java src/gametest/java/net/nuggetmc/tplus/gametest/BotArcheryTests.java
git commit -m "feat: wire archery into tickBot, at two points

The branch goes after the melee attack block and before the grounded
navigation block. Everything above is safety or melee; everything below is
movement. So returning there IS the hold-position decision, expressed as
ordering rather than as a flag -- the shape the rest of the method has.

The second touch point is the one that is easy to miss. tickBot returns
above the branch when the goal finds nothing, so a bot mid-draw whose
target dies would hold a drawn bow in a phase that never advances. The
reset goes beside the mining.stopMining that is already there for exactly
this reason, and a_drawing_bot_resets_when_its_target_disappears pins it --
invisible to every other test, since every other test keeps its target
alive.

a_ranged_bot_holds_its_ground deliberately ticks and waits, against the
usual convention. The warning is about move()'s Math.random() making a
position assertion measure the walk; asserting the absence of movement has
no jump and no random term.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Commands

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java` (tree around :145-167 and :220-226; handlers around :393 and :827)

**Interfaces:**
- Consumes: `Bot.setBow` (Task 3); `Archery.settings/setSettings/lastDecision` (Task 5); `RangedOverride`, `RangedSettings` (Task 1).
- Produces: no new Java API. Four command paths and one `/tplus info` line.

- [ ] **Step 1: Extend the `create` chain with a seventh argument**

In `register`, replace the innermost `item` node of the `create` chain (currently lines 164-166):

```java
                                                        .then(Commands.argument("item",
                                                                        ItemArgument.item(event.getBuildContext()))
                                                                .executes(ctx -> create(ctx, 6))
                                                                .then(Commands.argument("bow",
                                                                                ItemArgument.item(event.getBuildContext()))
                                                                        .executes(ctx -> create(ctx, 7))))))))));
```

- [ ] **Step 2: Read the bow in the `create` handler**

In `create`, immediately after the `ItemStack item = depth >= 6 ? ... : ItemStack.EMPTY;` block
(currently around line 444):

```java
        // Same reasoning as `item` above: built on the command thread, because the skin callback
        // runs on a worker and ItemStack construction reads data components.
        ItemStack bow = depth >= 7
                ? ItemArgument.getItem(ctx, "bow").createItemStack(1)
                : ItemStack.EMPTY;

        // `/tplus create Archer 3 none none none minecraft:bow` is what an operator types, and
        // Bot.hasBow honours it -- but at the cost of every melee hit, because the 1.8 table in
        // ItemUtils has no bow entry and falls through to FIST = 0.25. Say so rather than letting
        // them find out in a fight.
        if (bow.isEmpty() && item.getItem() instanceof net.minecraft.world.item.BowItem) {
            source.sendSuccess(() -> Component.literal(
                    "Note: the default item is a bow, so these bots will shoot but melee for 0.25"
                            + " damage. Pass a melee weapon as <item> and the bow as <bow> to get both.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
```

Then, inside the spawn loop, immediately after the existing `if (!item.isEmpty()) { ... }` block:

```java
                if (!bow.isEmpty()) {
                    bot.setBow(bow.copy());
                }
```

Add `final ItemStack bowStack = bow;` beside the other effectively-final locals the lambda
captures (`inList`, `armorTier`, `toolTier`) and use `bowStack` inside the loop if the compiler
requires it.

- [ ] **Step 3: Add the three new command trees**

In `register`, after the `descendrange` block (currently ending line 226):

```java
        root.then(Commands.literal("bow")
                .then(Commands.literal("none").executes(BotCommands::clearBow))
                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                        .executes(BotCommands::setBow)));

        root.then(Commands.literal("ranged")
                .executes(BotCommands::showRanged)
                .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> {
                            b.suggest("auto");
                            b.suggest("always");
                            b.suggest("never");
                            return b.buildFuture();
                        })
                        .executes(BotCommands::setRanged)));

        root.then(Commands.literal("towerquota")
                .executes(BotCommands::showTowerQuota)
                .then(Commands.argument("bots", IntegerArgumentType.integer(0))
                        .executes(BotCommands::setTowerQuota)));
```

- [ ] **Step 4: Write the handlers**

Beside `give` (around line 745), adding imports for
`net.nuggetmc.tplus.bot.ranged.RangedOverride`, `net.nuggetmc.tplus.bot.ranged.RangedSettings`
and `net.nuggetmc.tplus.agent.legacy.Archery`:

```java
    /**
     * Arms every bot with a bow, in the slot rather than as the default item.
     *
     * <p>Mirrors {@link #give}, and is separate from it for the reason the slot exists: a bot
     * carrying a bow as its default item melees at {@code FIST = 0.25}, because the 1.8 table in
     * {@code ItemUtils} has no bow entry.
     */
    private static int setBow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ItemStack stack = ItemArgument.getItem(ctx, "item").createItemStack(1);
        Collection<Bot> bots = TerminatorPlus.registry().bots();

        for (Bot bot : bots) {
            bot.setBow(stack.copy());
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Gave " + stack.getHoverName().getString() + " to " + bots.size()
                        + " bot(s) as a stowed bow"), true);

        warnIfPvpDisabled(ctx);
        return 1;
    }

    private static int clearBow(CommandContext<CommandSourceStack> ctx) {
        Collection<Bot> bots = TerminatorPlus.registry().bots();

        for (Bot bot : bots) {
            bot.setBow(ItemStack.EMPTY);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared the bow slot on " + bots.size() + " bot(s)"), true);
        return 1;
    }

    private static int showRanged(CommandContext<CommandSourceStack> ctx) {
        Archery archery = archery(ctx);

        if (archery == null) {
            return 0;
        }

        RangedOverride mode = archery.settings().override();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Ranged combat is " + mode.name().toLowerCase(java.util.Locale.ROOT)
                        + describeRangedMode(mode)), false);
        return 1;
    }

    private static String describeRangedMode(RangedOverride mode) {
        return switch (mode) {
            case AUTO -> " — the rules decide (target_flying, tower_quota, bot_stuck).";
            case ALWAYS -> " — bots shoot whenever the gates allow, ignoring the rules.";
            case NEVER -> " — no bot will draw.";
        };
    }

    private static int setRanged(CommandContext<CommandSourceStack> ctx) {
        Archery archery = archery(ctx);

        if (archery == null) {
            return 0;
        }

        String word = StringArgumentType.getString(ctx, "mode");
        RangedOverride mode;

        try {
            mode = RangedOverride.valueOf(word.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + word + "' must be 'auto', 'always' or 'never'"));
            return 0;
        }

        archery.setSettings(archery.settings().withOverride(mode));

        // A bot may be mid-draw when this is typed, and nothing else would clear it.
        if (mode == RangedOverride.NEVER) {
            for (Bot bot : TerminatorPlus.registry().bots()) {
                archery.reset(bot);
            }
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Ranged combat set to " + mode.name().toLowerCase(java.util.Locale.ROOT)
                        + describeRangedMode(mode)), true);

        if (mode != RangedOverride.NEVER) {
            warnIfPvpDisabled(ctx);
        }

        return 1;
    }

    private static int showTowerQuota(CommandContext<CommandSourceStack> ctx) {
        Archery archery = archery(ctx);

        if (archery == null) {
            return 0;
        }

        int quota = archery.settings().towerQuota();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Tower quota is " + quota + " — a bot shoots instead of towering once " + quota
                        + " squadmate(s) near the target are already towering."), false);
        return 1;
    }

    private static int setTowerQuota(CommandContext<CommandSourceStack> ctx) {
        Archery archery = archery(ctx);

        if (archery == null) {
            return 0;
        }

        int quota = IntegerArgumentType.getInteger(ctx, "bots");
        archery.setSettings(archery.settings().withTowerQuota(quota));

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Tower quota set to " + quota), true);
        return 1;
    }

    private static @Nullable Archery archery(CommandContext<CommandSourceStack> ctx) {
        LegacyAgent agent = legacyAgent(ctx);
        return agent == null ? null : agent.archery();
    }

    /**
     * Says so when the {@code pvp} gamerule would make every arrow a no-op.
     *
     * <p>26.2 moved {@code pvp} out of {@code server.properties} and into a gamerule. It gates
     * player-owned arrows twice — {@code AbstractArrow.canHitEntity} passes the arrow straight
     * through, and {@code ServerPlayer.hurtServer} refuses the damage — while the melee path
     * calls {@code hurtServer} directly and ignores it entirely. So with pvp off, melee bots
     * keep killing players and archer bots silently stop. That asymmetry is accepted (deviation
     * 42); this turns the silence into a sentence.
     */
    private static void warnIfPvpDisabled(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getLevel().isPvpAllowed()) {
            return;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Warning: the 'pvp' gamerule is off in this level, so bot arrows will pass"
                        + " straight through players. Melee is unaffected.")
                .withStyle(ChatFormatting.YELLOW), false);
    }
```

- [ ] **Step 5: Add the `/tplus info` line**

In `info`, add a `Ranged:` line to the component chain, after `"\n  Kills: " + bot.getKills()`:

```java
                        + "\n  Ranged: " + describeRanged(bot)
```

And add the helper beside `describeSkin`:

```java
    /**
     * A bot's ranged mode and the reason for it.
     *
     * <p>This line is the entire reason {@code RangedDecision} names a rule rather than returning
     * a score: "why is this bot not shooting?" is the question an operator actually asks, and a
     * scored decision cannot answer it.
     */
    private static String describeRanged(Bot bot) {
        Agent agent = bot.agent();

        if (!(agent instanceof LegacyAgent legacy)) {
            return "n/a";
        }

        RangedDecision decision = legacy.archery().lastDecision(bot);

        if (decision == null) {
            return bot.hasBow() ? "armed, no decision yet" : "no bow";
        }

        return decision.mode() + " (" + decision.reason().label() + ")";
    }
```

Add imports for `net.nuggetmc.tplus.agent.Agent` and
`net.nuggetmc.tplus.bot.ranged.RangedDecision` if not already present.

- [ ] **Step 6: Build and smoke-test over RCON**

Run: `./gradlew build`

Expected: PASS.

Then, in one terminal:

```bash
./gradlew runServer
```

And once it reports Done, in another — `tools/rcon.py` needs `python`, not `python3`:

```bash
python tools/rcon.py "tplus create Hunter 3 none none iron minecraft:netherite_sword minecraft:bow"
```

Then check each of these returns a sentence rather than an error:

```bash
python tools/rcon.py "tplus ranged" "tplus ranged always" "tplus towerquota" "tplus towerquota 5" "tplus bow minecraft:bow" "tplus info Hunter" "tplus bow none" "tplus ranged auto"
```

`tplus info Hunter` must show a `Ranged:` line. This tier is here because it catches command-tree
and server-runtime failures that GameTests pass straight over — a `ConfigSync` crash survived 51
GameTests once.

Stop the server before moving on, or the next `runServer` dies on the world lock with an opaque
IOException.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/command/BotCommands.java
git commit -m "feat: bow, ranged, towerquota commands and the info line

create gains a seventh argument on the existing fixed-depth chain, so a bot
can be given a melee weapon and a bow at once. Passing a bow as <item>
still works, because it is what an operator types, but now says out loud
that it costs every melee hit -- ItemUtils' 1.8 table has no bow entry and
falls through to FIST = 0.25.

/tplus ranged never resets every bot, since one may be mid-draw when it is
typed and nothing else would clear it.

/tplus bow and /tplus ranged warn when the pvp gamerule is off. 26.2 moved
pvp from server.properties to a gamerule, and it gates player-owned arrows
at two sites while the melee path calls hurtServer directly and ignores it.
So with pvp off melee bots keep killing and archers silently stop; the
warning turns that silence into a sentence.

The info line is why RangedDecision names a rule instead of returning a
score: 'why is this bot not shooting?' is the question operators ask.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: Documentation — the register, the API note, the README and the backlog

**Files:**
- Modify: `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` (deviation register, after entry 37 around :10079)
- Modify: `CLAUDE.md` (the "26.2 API notes" list)
- Modify: `README.md` (command reference; the "Bots cannot use items" note around :351)
- Modify: `docs/backlog.md` (the "Ranged attacks" and "Bots cannot use items" sections)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing executable.

- [ ] **Step 1: Append deviations 38-43 to Plan B's register**

Insert after entry 37, before the "And two things found in Plan D that are **not** deviations:"
line. The full text of the six entries is in the design spec's `## Deviations` section — copy it
verbatim from `docs/superpowers/specs/2026-09-14-bow-and-ranged-combat-design.md`, preserving
the numbering and the four-space continuation indent the existing entries use.

Do not start a new list. CLAUDE.md: *"Its deviation register is the one every later plan extends."*

- [ ] **Step 2: Add the 26.2 API note to CLAUDE.md**

In the `## 26.2 API notes` list, add:

```markdown
- **`pvp` is a gamerule now, not a `server.properties` flag.** `GameRules.PVP`,
  `registerBoolean("pvp", PLAYER, true)`, read per-level by `ServerLevel.isPvpAllowed()`. It gates
  player-owned arrows at two sites — `AbstractArrow.canHitEntity` passes the arrow through, and
  `ServerPlayer.hurtServer` refuses the damage — so it silently disables archer bots while melee
  bots, which call `hurtServer` directly, keep working. See deviation 42.
- **An arrow is born at `getEyeY() - 0.1F`, not at the eye,** and flies under gravity 0.05 with
  0.99 drag applied in that order *after* the move: `AbstractArrow.tick` moves, then
  `applyInertia`, then `applyGravity`. `BowBallistics` depends on all three facts.
- **`Level.getEntities` merges `dragonParts()` into every query,** so projectiles hit the Ender
  Dragon with no special handling — but they hit whichever part is in the way, and
  `EnderDragon.hurt` quarter-damages everything but the head.
```

- [ ] **Step 3: Update the README**

Add the four commands to the command reference table, matching its existing column layout:

```markdown
| `/tplus bow <item\|none>` | Gives every bot a stowed bow, or clears the slot |
| `/tplus ranged <auto\|always\|never>` | Whether bots may shoot. `auto` lets the rules decide |
| `/tplus towerquota <n>` | How many towering squadmates make the next bot shoot instead |
```

Update the `create` line to show seven arguments, and replace the example around line 107:

```markdown
/tplus create Hunter 5 none netherite diamond minecraft:netherite_sword minecraft:bow
```

with a sentence noting the bots carry a sword and swap to the bow when a rule fires.

Then correct the "Bots cannot use items" note around line 351. It currently claims food, potions,
bows and the shield all wait on one missing use-tick. Bows no longer do — say that they shipped by
bypassing `BowItem.releaseUsing`, and that **the other three are exactly as blocked as before**, so
a working bow is not read as evidence they are close.

- [ ] **Step 4: Update the backlog**

In `docs/backlog.md`:

- **"Ranged attacks"** — replace the section. It is built. Leave a short note saying what shipped
  (three rules, hold-position, `MAX_RANGE` 24) and what did not (`target_camping`, `unreachable`,
  `target_fleeing`, any retreat or range band), pointing at the spec's "Deliberately not built".
- **"Bots cannot use items"** — remove bows from the list of four. Keep food, potions and the
  shield, and add a sentence that the bow shipped *without* the use-tick, so the count of features
  the use-tick would unlock is three, not four.
- **"Bots suffocate each other"** — add that `tower_quota` reduces how many bots reach one column
  but does not fix it: `BlockScan.placeFinal` still has no entity-occupancy check.

- [ ] **Step 5: Verify the docs are true**

Run: `./gradlew build && ./gradlew runGameTestServer`

Expected: PASS on both. The docs claim behaviour; the suites are what make the claims true.

Then re-read the README's command reference against the actual Brigadier tree in
`BotCommands.register`. CLAUDE.md: *"Keep it true; it is the only document a stranger reads."*

- [ ] **Step 6: Commit**

```bash
git add docs/ CLAUDE.md README.md
git commit -m "docs: register deviations 38-43 and correct the backlog

The backlog filed bows under 'bots cannot use items', waiting on the same
use-tick food, potions and the shield need. That was wrong for bows: only
BowItem.releaseUsing's first step reads the frozen useItemRemaining counter,
and only to recover a number the caller already knows. Bows shipped without
it, and the other three are exactly as blocked as before -- the count the
use-tick would unlock is three, not four.

CLAUDE.md gains three 26.2 notes found while building this: pvp is a
gamerule now and gates player-owned arrows at two sites while melee ignores
it; an arrow is born at getEyeY() - 0.1F and integrates move-then-drag-then-
gravity; and Level.getEntities merges dragonParts into every query.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Verification

After Task 8, the whole feature is in. Confirm:

```bash
./gradlew build
```

Expected: 109 existing unit tests plus 29 new ones (19 `RangedDecisionTest`, 10 `BowBallisticsTest`), clean compile.

```bash
./gradlew runGameTestServer
```

Expected: 147 existing GameTests plus 21 new `bot.archery` tests, all green.

**Then a real client session, which is not optional.** The draw pose, the arrow trail and the release sound are invisible to every other tier, and the draw animation rides a packet path no automated test can observe. This project's own record is the argument: a manual session found four bugs that 132 GameTests and three RCON runs had passed over, including an entity packet sent before player info and a missing skin-layer mask.

```bash
./gradlew runClient
```

Connect to localhost and check, in order:

1. `/tplus create Hunter 3 none none iron minecraft:netherite_sword minecraft:bow` — bots spawn holding a **sword**.
2. Fly up 15 blocks and hover for three seconds. Bots stop walking, swap to the **bow**, and visibly draw.
3. The draw pose appears *and clears* — a bow stuck at full draw means `stopUsingItem` or the entity-data broadcast is missing.
4. Arrows render in flight and arc toward you rather than flying flat.
5. The release sound fires **once** per shot, not every tick.
6. Land and walk within 4 blocks. Bots swap back to the sword and punch.
7. `/tplus info Hunter` shows a `Ranged:` line whose reason matches what you just watched.

Bots need a player nearby to tick — `/forceload` is not enough. If nothing happens, check `Alive ticks` is climbing in `/tplus info`.
