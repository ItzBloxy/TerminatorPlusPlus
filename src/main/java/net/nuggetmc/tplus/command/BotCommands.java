package net.nuggetmc.tplus.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.TargetGoal;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.util.MojangSkins;

import java.util.stream.Collectors;
import java.util.function.Consumer;

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
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tplus")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

        // The trailing 'playerlist' literal puts the bots in the real PlayerList, so the server
        // counts them as online players and selectors like @a reach them. Upstream made this a
        // sticky global setting ("settings addplayerlist"); Brigadier makes per-invocation the
        // natural shape, and a hidden global that silently changes what create does is worse for
        // an operator than an explicit argument.
        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> create(ctx, 1, false))
                        .then(Commands.literal("playerlist")
                                .executes(ctx -> create(ctx, 1, true)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_BOTS_PER_COMMAND))
                                .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "count"), false))
                                .then(Commands.literal("playerlist")
                                        .executes(ctx -> create(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"), true))))));

        root.then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(BotCommands::removeOne)));

        root.then(Commands.literal("goal")
                .executes(BotCommands::showGoal)
                .then(Commands.argument("goal", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (TargetGoal goal : TargetGoal.values()) {
                                builder.suggest(goal.name().toLowerCase().replace("_", ""));
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommands::setGoal)));

        root.then(Commands.literal("agent")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean on = BoolArgumentType.getBool(ctx, "enabled");
                            TerminatorPlus.registry().agent().setEnabled(on);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Agent " + (on ? "enabled" : "disabled")), true);
                            return 1;
                        })));

        root.then(Commands.literal("drops")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean on = BoolArgumentType.getBool(ctx, "enabled");
                            TerminatorPlus.registry().agent().setDrops(on);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Bot drops " + (on ? "enabled" : "disabled")), true);
                            return 1;
                        })));

        root.then(Commands.literal("offsets")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean on = BoolArgumentType.getBool(ctx, "enabled");

                            if (TerminatorPlus.registry().agent() instanceof LegacyAgent agent) {
                                agent.offsets = on;
                            }

                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Target offsets " + (on ? "enabled" : "disabled")), true);
                            return 1;
                        })));

        root.then(Commands.literal("removeall").executes(BotCommands::removeAll));
        root.then(Commands.literal("list").executes(BotCommands::list));

        // Drives the action API by hand. This is how phase 1 is verified outside a GameTest:
        // stand in front of a bot and make it do things.
        root.then(Commands.literal("bot")
                .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.literal("punch").executes(ctx -> act(ctx, Bot::punch)))
                        .then(Commands.literal("sneak").executes(ctx -> act(ctx, Bot::sneak)))
                        .then(Commands.literal("stand").executes(ctx -> act(ctx, Bot::stand)))
                        .then(Commands.literal("swim").executes(ctx -> act(ctx, Bot::swim)))
                        .then(Commands.literal("lookdown")
                                .executes(ctx -> act(ctx, bot -> bot.look(Direction.DOWN))))
                        .then(Commands.literal("lookup")
                                .executes(ctx -> act(ctx, bot -> bot.look(Direction.UP))))
                        .then(Commands.literal("faceme")
                                .executes(ctx -> act(ctx, bot -> bot.faceLocation(ctx.getSource().getPosition()))))
                        .then(Commands.literal("shield")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> act(ctx, bot ->
                                                bot.setShield(BoolArgumentType.getBool(ctx, "enabled"))))))
                        .then(Commands.literal("hold")
                                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                        .executes(BotCommands::hold)))));

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

    private static int showGoal(CommandContext<CommandSourceStack> ctx) {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        TargetGoal goal = agent.targeting().getTargetType();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Goal: " + goal.name() + " — " + goal.description()), false);
        return 1;
    }

    private static int setGoal(CommandContext<CommandSourceStack> ctx) {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "goal");
        TargetGoal goal = TargetGoal.from(name);

        if (goal == null) {
            ctx.getSource().sendFailure(Component.literal("No such goal: '" + name + "'"));
            return 0;
        }

        agent.targeting().setTargetType(goal);
        ctx.getSource().sendSuccess(() -> Component.literal("Goal set to " + goal.name()), true);
        return 1;
    }

    /** Looks up a bot by name and applies {@code action} to it. */
    private static int act(CommandContext<CommandSourceStack> ctx, Consumer<Bot> action) {
        String name = StringArgumentType.getString(ctx, "name");
        Bot bot = TerminatorPlus.registry().byName(name);

        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("No bot named '" + name + "'"));
            return 0;
        }

        action.accept(bot);
        return 1;
    }

    /**
     * Separate from {@link #act} because {@code createItemStack} throws a checked
     * {@code CommandSyntaxException}, which a {@code Consumer} cannot declare.
     */
    private static int hold(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        // 26.2 dropped the second parameter: createItemStack(int), not (int, boolean).
        ItemStack stack = ItemArgument.getItem(ctx, "item").createItemStack(1);

        return act(ctx, bot -> bot.setItem(stack));
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
