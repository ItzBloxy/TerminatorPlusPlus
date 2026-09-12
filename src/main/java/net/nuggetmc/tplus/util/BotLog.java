package net.nuggetmc.tplus.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.bot.Bot;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Debug output that reaches both the server log and whoever is standing there.
 *
 * <p>Ported from {@code api/utils/DebugLogUtils}. Its one upstream caller
 * ({@code CommandHandler}) is deleted along with the reflection command framework, so see
 * plan correction 6 for why this is kept anyway.
 *
 * <p>{@code MCLogs} is deliberately not merged in here despite what spec §4.4 says: it is a
 * mclo.gs paste uploader wrapped around the deleted version check, not a logger.
 */
public final class BotLog {

    private static final String PREFIX = "[DEBUG] ";

    private BotLog() {
    }

    /**
     * Logs {@code values}, space-joined, to the mod logger and to every online operator.
     *
     * <p>{@code server} may be null — during shutdown, and in a unit test — in which case
     * only the logger is used.
     */
    public static void debug(MinecraftServer server, Object... values) {
        String message = join(values);

        TerminatorPlus.LOGGER.info("{}{}", PREFIX, message);

        if (server == null) {
            return;
        }

        Component component = Component.literal(PREFIX)
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(message).withStyle(ChatFormatting.RESET));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Bots are ServerPlayers too, and one in the PlayerList would otherwise get sent
            // chat down a connection that goes nowhere. Upstream had the same hole; skipping
            // them costs nothing and matches BotFactory.broadcast.
            if (player instanceof Bot) {
                continue;
            }

            // 26.2 moved the op check: PlayerList.isOp takes a NameAndId, not a GameProfile.
            // Player.nameAndId() builds one from the profile.
            if (server.getPlayerList().isOp(player.nameAndId())) {
                player.sendSystemMessage(component);
            }
        }
    }

    static String join(Object[] values) {
        return Arrays.stream(values).map(String::valueOf).collect(Collectors.joining(" "));
    }
}
