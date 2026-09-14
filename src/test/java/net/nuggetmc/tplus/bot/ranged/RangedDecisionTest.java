package net.nuggetmc.tplus.bot.ranged;

import org.junit.jupiter.api.Test;

import java.util.List;

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
        // one is packed in the middle. It charges rather than firing into the backs of its own
        // squad, and charging is what de-crowds it.
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

        List<Case> cases = List.of(
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
