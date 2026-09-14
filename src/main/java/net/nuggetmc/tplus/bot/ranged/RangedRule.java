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
