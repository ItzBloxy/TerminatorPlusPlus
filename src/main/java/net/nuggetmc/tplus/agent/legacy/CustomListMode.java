package net.nuggetmc.tplus.agent.legacy;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which goals consult {@link Targeting#CUSTOM_MOB_LIST}. Ported verbatim.
 *
 * <p>Spec §4.4 defers this with the rest of the AI, but four of the eleven goals branch on it,
 * so omitting it would mean rewriting those branches — exactly what a faithful translation is
 * trying to avoid. What stays deferred is its only writer, {@code BotEnvironmentCommand}, so the
 * custom list is always empty in v1 and {@code CUSTOM_LIST} finds nothing. That is identical to
 * upstream's behaviour with an unconfigured list.
 */
public enum CustomListMode {
    HOSTILE,
    RAIDER,
    MOB,
    CUSTOM;

    public static boolean isValid(String name) {
        return from(name) != null;
    }

    public static CustomListMode from(String name) {
        for (CustomListMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return null;
    }

    public static String listModes() {
        return Arrays.stream(values()).map(e -> e.name().toLowerCase()).collect(Collectors.joining("|"));
    }
}
