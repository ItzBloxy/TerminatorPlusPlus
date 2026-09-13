package net.nuggetmc.tplus.agent.legacy;

import java.util.HashMap;
import java.util.Map;

/**
 * Ported from {@code EnumTargetGoal}; only the name is shortened.
 *
 * <p>{@link #ENTITY} is the one addition, for {@code /tplus enemytarget}, which upstream had no
 * equivalent of. Note that {@link #PLAYER}'s description is upstream's and is <b>wrong</b>: the
 * branch does not fall back to NEAREST_VULNERABLE_PLAYER, it returns null. ENTITY's description
 * says what its branch actually does.
 */
public enum TargetGoal {
    NEAREST_VULNERABLE_PLAYER("Locate the nearest real player that is in either Survival or Adventure mode."),
    NEAREST_PLAYER("Locate the nearest real online player, despite the gamemode."),
    NEAREST_HOSTILE("Locate the nearest hostile entity."),
    NEAREST_RAIDER("Locate the nearest raider."),
    NEAREST_MOB("Locate the nearest mob."),
    NEAREST_BOT("Locate the nearest bot."),
    NEAREST_BOT_DIFFER("Locate the nearest bot with a different username."),
    NEAREST_BOT_DIFFER_ALPHA("Locate the nearest bot with a different username after filtering out non-alpha characters."),
    CUSTOM_LIST("Locate only the mob types specified in the custom list of mobs"),
    PLAYER("Target a single player. Defaults to NEAREST_VULNERABLE_PLAYER if no player found."),
    ENTITY("Target the entities or entity types set by /tplus enemytarget. No target if none match."),
    NONE("No target goal.");

    private static final Map<String, TargetGoal> VALUES = new HashMap<>();

    static {
        for (TargetGoal goal : values()) {
            VALUES.put(goal.name().toLowerCase().replace("_", ""), goal);
        }
    }

    private final String description;

    TargetGoal(String description) {
        this.description = description;
    }

    /**
     * @return the goal with this name, or null. Callers distinguish null from {@link #NONE}.
     */
    public static TargetGoal from(String name) {
        return VALUES.get(name);
    }

    public String description() {
        return description;
    }
}
