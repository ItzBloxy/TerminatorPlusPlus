package net.nuggetmc.tplus.agent.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure: the enum has no game dependency. */
class TargetGoalTest {

    @Test
    void everyGoalIsReachableByItsLowercaseName() {
        // Upstream's VALUES map is hand-maintained and hand-keyed, so a goal can be added to
        // the enum and silently be unreachable from the command. This catches that.
        for (TargetGoal goal : TargetGoal.values()) {
            String key = goal.name().toLowerCase().replace("_", "");
            assertEquals(goal, TargetGoal.from(key), "goal not reachable by name: " + key);
        }
    }

    @Test
    void anUnknownNameIsNullRatherThanADefault() {
        // Callers distinguish "no such goal" from NONE, so from() must not fall back.
        assertNull(TargetGoal.from("nonsense"));
        assertNotNull(TargetGoal.from("none"));
    }

    @Test
    void everyGoalHasADescription() {
        for (TargetGoal goal : TargetGoal.values()) {
            assertNotNull(goal.description(), goal + " has no description");
        }
    }

    @Test
    void theElevenUpstreamGoalsAreAllPresent() {
        // Pinned as a count so a goal cannot be quietly dropped in translation; upstream's
        // EnumTargetGoal had exactly these eleven.
        assertEquals(11, TargetGoal.values().length);

        for (String name : new String[]{"none", "nearestvulnerableplayer", "nearestplayer",
                "nearesthostile", "nearestraider", "nearestmob", "nearestbot",
                "nearestbotdiffer", "nearestbotdifferalpha", "customlist", "player"}) {
            assertNotNull(TargetGoal.from(name), "upstream key missing: " + name);
        }
    }

    @Test
    void customListModeParsesCaseInsensitively() {
        assertEquals(CustomListMode.HOSTILE, CustomListMode.from("hostile"));
        assertEquals(CustomListMode.HOSTILE, CustomListMode.from("HOSTILE"));
        assertNull(CustomListMode.from("nope"));
    }
}
