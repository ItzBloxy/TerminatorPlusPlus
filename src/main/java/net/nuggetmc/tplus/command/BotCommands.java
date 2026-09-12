package net.nuggetmc.tplus.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.util.MojangSkins;

import java.util.stream.Collectors;

/**
 * The {@code /tplus} command tree.
 *
 * <p>Gated at the gamemaster tier, the 26.2 equivalent of the old permission level 2,
 * matching the Paper build's {@code terminatorplus.manage} permission.
 *
 * <p>26.2 replaced {@code CommandSourceStack.hasPermission(int)} with a real permission
 * system: {@code Commands.hasPermission(PermissionCheck)} returns a
 * {@code PermissionProviderCheck}, which implements {@link java.util.function.Predicate}
 * and so can be handed straight to {@code requires}.
 */
public final class BotCommands {

    private static final int MAX_BOTS_PER_COMMAND = 100;

    private BotCommands() {
    }

    // Names use StringArgumentType.string(), not word(): Brigadier's unquoted-string
    // rules reject '%', and '%' is upstream's index placeholder ("Bot%" -> Bot1..BotN).
    // string() still accepts a bare word, so /tplus create Alice 3 works unquoted while
    // /tplus create "Bot%" 4 works quoted.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tplus")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> create(ctx, 1, false))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_BOTS_PER_COMMAND))
                                .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "count"), false)))));
        // No 'playerlist' subcommand: that spawn path is unsupported on NeoForge
        // (PlayerList.getPlayers() is an unmodifiable view). See BotFactory.spawn.

        root.then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(BotCommands::removeOne)));

        root.then(Commands.literal("removeall").executes(BotCommands::removeAll));
        root.then(Commands.literal("list").executes(BotCommands::list));

        dispatcher.register(root);
    }

    private static int create(CommandContext<CommandSourceStack> ctx, int count, boolean playerList) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Fetching skin for " + name + "..."), false);

        MojangSkins.fetch(name).thenAccept(skin -> BotRegistry.onServerThread(server, () -> {
            // Scatter factor, from BotManagerImpl.createBots: bots after the first get a
            // nudge so a batch spawned on one spot does not stack up.
            double f = count < 100 ? 0.004 * count : 0.4;

            for (int i = 1; i <= count; i++) {
                String botName = BotGameProfiles.indexedName(name, i);
                GameProfile profile = BotGameProfiles.create(botName, skin);

                Bot bot = BotFactory.spawn(TerminatorPlus.registry(), level, pos,
                        source.getRotation().y, source.getRotation().x, profile, playerList);

                if (i > 1) {
                    bot.getBotVelocity()
                            .setX(Math.random() - 0.5)
                            .setY(0.5)
                            .setZ(Math.random() - 0.5)
                            .normalize()
                            .multiply(f);
                }
            }

            source.sendSuccess(() -> Component.literal(
                    "Spawned " + count + " bot(s)" + (playerList ? " in the player list" : "")), true);
        }));

        return count;
    }

    private static int removeOne(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Bot bot = TerminatorPlus.registry().byName(name);

        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("No bot named '" + name + "'"));
            return 0;
        }

        TerminatorPlus.registry().remove(bot);
        bot.removeBot();
        ctx.getSource().sendSuccess(() -> Component.literal("Removed bot '" + name + "'"), true);

        return 1;
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) {
        int removed = TerminatorPlus.registry().size();
        TerminatorPlus.registry().reset();

        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " bot(s)"), true);
        return removed;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var bots = TerminatorPlus.registry().bots();

        if (bots.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No bots are loaded."), false);
            return 0;
        }

        String names = bots.stream()
                .map(bot -> bot.getGameProfile().name())
                .sorted()
                .collect(Collectors.joining(", "));

        ctx.getSource().sendSuccess(
                () -> Component.literal(bots.size() + " bot(s): " + names), false);

        return bots.size();
    }
}
