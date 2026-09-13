package net.nuggetmc.tplus.agent.legacy;

import java.util.HashMap;
import java.util.Map;

/** Ported verbatim from {@code EnumTargetGoal}; only the name is shortened. */
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
