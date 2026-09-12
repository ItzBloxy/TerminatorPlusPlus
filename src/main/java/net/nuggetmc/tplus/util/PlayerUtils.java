package net.nuggetmc.tplus.util;

import net.minecraft.world.level.GameType;

/**
 * Ported from {@code api/utils/PlayerUtils}, which had five methods. Four are not ported:
 * {@code randomName} and {@code fillUsernameCache} have no caller anywhere on {@code master},
 * {@code findBottom} likewise, and {@code findAbove}'s only caller is {@code IntelligenceAgent},
 * deferred with the neural-network AI. See plan correction 6.
 */
public final class PlayerUtils {

    private PlayerUtils() {
    }

    /**
     * True when a player in this mode cannot be hurt.
     *
     * <p>Verbatim from upstream, including the null branch: a null mode counts as
     * vulnerable, not invincible. {@code Targeting} calls this for every candidate every
     * tick, so the null case is reachable during a gamemode change.
     */
    public static boolean isInvincible(GameType mode) {
        return mode != GameType.SURVIVAL && mode != GameType.ADVENTURE && mode != null;
    }
}
