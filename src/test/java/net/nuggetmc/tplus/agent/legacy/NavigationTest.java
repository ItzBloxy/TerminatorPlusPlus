package net.nuggetmc.tplus.agent.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure: the horizontal gate on {@code checkDown}'s first branch.
 *
 * <p>The whole of this port's descent change is one boolean expression, and it is deliberately
 * shaped so it can be tested without a world — no level, no bot, no {@code ItemStack}. The
 * arguments are exactly what {@code tickBot} already computes.
 */
class NavigationTest {

    private static final int RANGE = 8;

    @Test
    void aBotBesideItsTargetDigsWithoutBeingStuck() {
        assertTrue(Navigation.mayDigDown(true, false, 2, RANGE));
    }

    @Test
    void aBotBesideItsTargetDigsWhileStuckToo() {
        assertTrue(Navigation.mayDigDown(true, true, 2, RANGE));
    }

    @Test
    void aStuckBotInsideTheRangeDigs() {
        assertTrue(Navigation.mayDigDown(false, true, 5, RANGE));
    }

    @Test
    void aStuckBotOutsideTheRangeDoesNot() {
        // The whole point of the change. Upstream dug here, at any distance at all.
        assertFalse(Navigation.mayDigDown(false, true, 20, RANGE));
    }

    @Test
    void aDistantBotThatIsNotStuckNeverDug() {
        // Upstream's behaviour too -- this branch always needed one of the two flags.
        assertFalse(Navigation.mayDigDown(false, false, 20, RANGE));
    }

    @Test
    void withinTargetXZBypassesTheRangeEntirely() {
        // The case that earns its keep. withinTargetXZ is a 7x7 box around the AIM POINT, and
        // the aim point sits up to 3 blocks from the target (BotMath.circleOffset), so it admits
        // bots up to 8.66 blocks from the target itself. Collapsing this expression to
        // `horizontal < range` would veto bots the rest of tickBot already treats as adjacent.
        assertTrue(Navigation.mayDigDown(true, true, 8.5, RANGE));
    }

    @Test
    void unlimitedRestoresUpstreamsBehaviour() {
        assertTrue(Navigation.mayDigDown(false, true, 500,
                LegacyAgent.DESCEND_RANGE_UNLIMITED));
    }

    @Test
    void aRangeOfZeroStopsStuckBotsDescendingAtAll() {
        // Coherent rather than degenerate: the far end of the same dial. Only withinTargetXZ
        // bots descend, and they are not consulting the range.
        assertFalse(Navigation.mayDigDown(false, true, 0.5, 0));
        assertTrue(Navigation.mayDigDown(true, true, 0.5, 0));
    }
}
