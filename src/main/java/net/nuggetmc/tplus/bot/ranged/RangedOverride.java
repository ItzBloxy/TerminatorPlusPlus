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
