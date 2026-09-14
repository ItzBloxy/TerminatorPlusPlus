package net.nuggetmc.tplus.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.agent.legacy.BlockRules;
import net.nuggetmc.tplus.agent.legacy.CustomListMode;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.TargetGoal;
import net.nuggetmc.tplus.agent.legacy.Targeting;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.bot.EnemyTarget;
import net.nuggetmc.tplus.bot.EquipmentTier;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.motion.MotionVec;
import net.nuggetmc.tplus.util.MojangSkins;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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

    /**
     * The seven armor tiers, ported from {@code BotCommand.armorTierSetup}.
     *
     * <p>Ordered boots, leggings, chestplate, helmet — upstream's order, matching Bukkit's
     * {@code setArmorContents}. The {@code none} tier is four empty slots, which is how armor is
     * taken off again.
     *
     * <p>{@code Item} constants rather than stacks: a stack built in a static initialiser throws
     * "Components not bound yet", the same trap {@code Mining.TOOLS} documents.
     */
    private BotCommands() {
    }

    /** Suggests only the tiers that will actually parse in this slot. */
    private static SuggestionProvider<CommandSourceStack> tierSuggestions(boolean armor) {
        return (ctx, builder) -> {
            (armor ? EquipmentTier.armorTiers() : EquipmentTier.toolTiers()).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    /**
     * Resolves a tier name for one slot, or sends the failure and returns null.
     *
     * <p>The valid names come from the same predicate {@link #tierSuggestions} reads, so a tier
     * that tab-completes but then fails to parse cannot happen. Vanilla tiers are not symmetric —
     * there is no wooden chestplate and no chainmail pickaxe — so the two slots reject different
     * things and the message has to say which slot it is talking about.
     */
    private static @Nullable EquipmentTier tier(CommandSourceStack source, String name, boolean armor) {
        EquipmentTier tier = EquipmentTier.byName(name);
        boolean ok = tier != null && (armor ? tier.acceptsAsArmor() : tier.acceptsAsTools());

        if (!ok) {
            source.sendFailure(Component.literal("'" + name + "' is not a valid "
                    + (armor ? "armour" : "tools") + " tier. Available: "
                    + String.join(", ", armor ? EquipmentTier.armorTiers() : EquipmentTier.toolTiers())));
            return null;
        }

        return tier;
    }

    // Names use StringArgumentType.string(), not word(): Brigadier's unquoted-string
    // rules reject '%', and '%' is upstream's index placeholder ("Bot%" -> Bot1..BotN).
    // string() still accepts a bare word, so /tplus create Alice 3 works unquoted while
    // /tplus create "Bot%" 4 works quoted.
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tplus")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

        // Six nodes, each executable, because Brigadier has no optional-in-the-middle argument: a
        // chain grows left to right and reaching a later argument means typing the earlier ones.
        // `none` is the filler for the two word slots; <item> is last because it is the only
        // argument with no sensible filler.
        //
        // The playerlist slot puts the bots in the real PlayerList, so the server counts them as
        // online players and selectors like @a reach them. Upstream made this a sticky global
        // setting ("settings addplayerlist"); Brigadier makes per-invocation the natural shape,
        // and a hidden global that silently changes what create does is worse for an operator
        // than an explicit argument. It was a literal until this chain needed a fixed depth for
        // it -- same spelling, same effect, but `create <name> playerlist` with the count
        // omitted is now `create <name> 1 playerlist`.
        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> create(ctx, 1))
                        .then(Commands.argument("count",
                                        IntegerArgumentType.integer(1, MAX_BOTS_PER_COMMAND))
                                .executes(ctx -> create(ctx, 2))
                                .then(Commands.argument("playerlist", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            b.suggest("none");
                                            b.suggest("playerlist");
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> create(ctx, 3))
                                        .then(Commands.argument("armor", StringArgumentType.word())
                                                .suggests(tierSuggestions(true))
                                                .executes(ctx -> create(ctx, 4))
                                                .then(Commands.argument("tools", StringArgumentType.word())
                                                        .suggests(tierSuggestions(false))
                                                        .executes(ctx -> create(ctx, 5))
                                                        .then(Commands.argument("item",
                                                                        ItemArgument.item(event.getBuildContext()))
                                                                .executes(ctx -> create(ctx, 6)))))))));

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

        // Not the offsets block's inline `instanceof LegacyAgent`, which silently succeeds when
        // no legacy agent is installed. That is harmless for a write-only toggle and wrong for a
        // command that also reports -- it would print a number governing nothing.
        root.then(Commands.literal("descendrange")
                .executes(BotCommands::showDescendRange)
                .then(Commands.literal("unlimited")
                        .executes(ctx -> setDescendRange(ctx, LegacyAgent.DESCEND_RANGE_UNLIMITED)))
                .then(Commands.argument("blocks", IntegerArgumentType.integer(0))
                        .executes(ctx -> setDescendRange(ctx,
                                IntegerArgumentType.getInteger(ctx, "blocks")))));

        // Seven arguments in the worst case, so the coordinates come as two block positions
        // and the weights are optional on the end.
        root.then(Commands.literal("region")
                .executes(BotCommands::showRegion)
                .then(Commands.literal("clear").executes(BotCommands::clearRegion))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(ctx -> setRegion(ctx, 0, 0, 0))
                                .then(Commands.argument("weightX", DoubleArgumentType.doubleArg(0))
                                        .then(Commands.argument("weightY", DoubleArgumentType.doubleArg(0))
                                                .then(Commands.argument("weightZ", DoubleArgumentType.doubleArg(0))
                                                        .executes(ctx -> setRegion(ctx,
                                                                DoubleArgumentType.getDouble(ctx, "weightX"),
                                                                DoubleArgumentType.getDouble(ctx, "weightY"),
                                                                DoubleArgumentType.getDouble(ctx, "weightZ")))))))));

        // Two settings that had state and no way to reach it: task 8 added mobTarget and the
        // listener that reads it, task 4 added setTargetPlayer and task 10's PLAYER goal reads
        // it, and neither had a command until now.
        root.then(Commands.literal("mobtarget")
                .executes(ctx -> {
                    boolean on = TerminatorPlus.registry().isMobTarget();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Mob targeting is " + (on ? "enabled" : "disabled")), false);
                    return 1;
                })
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean on = BoolArgumentType.getBool(ctx, "enabled");
                            TerminatorPlus.registry().setMobTarget(on);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Mob targeting is now " + (on ? "enabled" : "disabled")), true);
                            return 1;
                        })));

        root.then(Commands.literal("playertarget")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(BotCommands::setPlayerTarget)));

        // Two modes rather than one command that guesses. `generic` is live and by type, so a
        // zombie that spawns later is hunted too; `specific` is fixed and by entity, so it is
        // not. The mode is stated because the two answer the same question opposite ways, and
        // inferring it from how many entities a selector happened to match would make the same
        // command mean different things depending on what was standing around.
        root.then(Commands.literal("enemytarget")
                .executes(BotCommands::showEnemyTarget)
                .then(Commands.literal("clear")
                        .executes(BotCommands::clearEnemyTarget))
                .then(Commands.literal("generic")
                        .then(Commands.argument("type", ResourceOrTagArgument.resourceOrTag(
                                        event.getBuildContext(), Registries.ENTITY_TYPE))
                                .executes(BotCommands::enemyTargetGeneric)))
                .then(Commands.literal("specific")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(BotCommands::enemyTargetSpecific))));

        root.then(Commands.literal("give")
                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                        .executes(BotCommands::give)));

        root.then(Commands.literal("armor")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .suggests(tierSuggestions(true))
                        .executes(BotCommands::armor)));

        root.then(Commands.literal("tools")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .suggests(tierSuggestions(false))
                        .executes(BotCommands::tools)));

        root.then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (Bot bot : TerminatorPlus.registry().bots()) {
                                builder.suggest(bot.getBotName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommands::info)));

        // Upstream's /botenvironment, as a subtree rather than a second root: one permission
        // gate, one registration, one help. Both add/remove forms take their location variant
        // under an `at` literal, so Brigadier reports no ambiguity between a block id and a
        // position.
        root.then(Commands.literal("environment")
                .then(Commands.literal("help")
                        .executes(ctx -> environmentHelp(ctx, ""))
                        .then(Commands.literal("blocks").executes(ctx -> environmentHelp(ctx, "blocks")))
                        .then(Commands.literal("mobs").executes(ctx -> environmentHelp(ctx, "mobs"))))
                .then(Commands.literal("getblock")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(BotCommands::getBlock)))
                .then(Commands.literal("addsolid")
                        .then(Commands.argument("block",
                                        ResourceArgument.resource(event.getBuildContext(), Registries.BLOCK))
                                .executes(ctx -> setSolid(ctx, blockArgument(ctx), true)))
                        .then(Commands.literal("at")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> setSolid(ctx, blockAtPosition(ctx), true)))))
                .then(Commands.literal("removesolid")
                        .then(Commands.argument("block",
                                        ResourceArgument.resource(event.getBuildContext(), Registries.BLOCK))
                                .executes(ctx -> setSolid(ctx, blockArgument(ctx), false)))
                        .then(Commands.literal("at")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> setSolid(ctx, blockAtPosition(ctx), false)))))
                .then(Commands.literal("listsolids").executes(BotCommands::listSolids))
                .then(Commands.literal("clearsolids").executes(BotCommands::clearSolids))
                .then(Commands.literal("addmob")
                        .then(Commands.argument("type",
                                        ResourceArgument.resource(event.getBuildContext(), Registries.ENTITY_TYPE))
                                .executes(ctx -> setMob(ctx, true))))
                .then(Commands.literal("removemob")
                        .then(Commands.argument("type",
                                        ResourceArgument.resource(event.getBuildContext(), Registries.ENTITY_TYPE))
                                .executes(ctx -> setMob(ctx, false))))
                .then(Commands.literal("listmobs").executes(BotCommands::listMobs))
                .then(Commands.literal("clearmobs").executes(BotCommands::clearMobs))
                .then(Commands.literal("moblisttype")
                        .executes(BotCommands::showMobListType)
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (CustomListMode mode : CustomListMode.values()) {
                                        builder.suggest(mode.name().toLowerCase(Locale.ROOT));
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(BotCommands::setMobListType))));

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

    /**
     * Spawns bots, optionally equipped.
     *
     * @param depth how far along the six-node chain the parse got, and therefore which arguments
     *              exist. Brigadier offers no way to ask a {@link CommandContext} whether an
     *              argument was present — {@code getArgument} throws for a missing name — so the
     *              node that matched says so rather than the handler probing for it.
     */
    private static int create(CommandContext<CommandSourceStack> ctx, int depth)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        int count = depth >= 2 ? IntegerArgumentType.getInteger(ctx, "count") : 1;

        boolean playerList = false;

        if (depth >= 3) {
            String word = StringArgumentType.getString(ctx, "playerlist");

            if (word.equalsIgnoreCase("playerlist")) {
                playerList = true;
            } else if (!word.equalsIgnoreCase("none")) {
                source.sendFailure(Component.literal(
                        "'" + word + "' must be 'playerlist' or 'none'"));
                return 0;
            }
        }

        // The two defaults are not symmetric, and cannot be. An omitted armour argument means no
        // armour, which is what a bot has always spawned with; an omitted tools argument means
        // IRON, which is also what a bot has always spawned with. Typing `none` for tools is a
        // third thing -- the floor, wood.
        EquipmentTier armor = EquipmentTier.NONE;
        EquipmentTier tools = EquipmentTier.IRON;

        if (depth >= 4) {
            armor = tier(source, StringArgumentType.getString(ctx, "armor"), true);

            if (armor == null) {
                return 0;
            }
        }

        if (depth >= 5) {
            tools = tier(source, StringArgumentType.getString(ctx, "tools"), false);

            if (tools == null) {
                return 0;
            }

            // `none` in the tools slot is the chain's filler and floors at wood. Resolved here so
            // the feedback names what the bots actually got; setToolTier applies the same clamp
            // regardless.
            tools = tools.asToolTier();
        }

        // Built here, on the command thread, rather than inside the async skin callback: the
        // callback runs on a worker until onServerThread hands it back, and ItemStack
        // construction reads data components.
        ItemStack item = depth >= 6
                ? ItemArgument.getItem(ctx, "item").createItemStack(1)
                : ItemStack.EMPTY;

        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        MinecraftServer server = source.getServer();

        boolean inList = playerList;
        EquipmentTier armorTier = armor;
        EquipmentTier toolTier = tools;

        source.sendSuccess(() -> Component.literal("Fetching skin for " + name + "..."), false);

        MojangSkins.fetch(name).thenAccept(skin -> BotRegistry.onServerThread(server, () -> {
            // Scatter factor, from BotManagerImpl.createBots: bots after the first get a
            // nudge so a batch spawned on one spot does not stack up.
            double f = count < 100 ? 0.004 * count : 0.4;

            for (int i = 1; i <= count; i++) {
                String botName = BotGameProfiles.indexedName(name, i);
                GameProfile profile = BotGameProfiles.create(botName, skin);

                Bot bot = BotFactory.spawn(TerminatorPlus.registry(), level, pos,
                        source.getRotation().y, source.getRotation().x, profile, inList);

                bot.setToolTier(toolTier);
                armorTier.equipArmor(bot);

                if (!item.isEmpty()) {
                    // Same pair /tplus give does: the default item is what setItem(null) restores
                    // and what ItemUtils scores for damage, and putting it in hand now is what
                    // someone typing the command expects.
                    bot.setDefaultItem(item.copy());
                    bot.setItem(null);
                }

                if (i > 1) {
                    bot.getBotVelocity()
                            .setX(Math.random() - 0.5)
                            .setY(0.5)
                            .setZ(Math.random() - 0.5)
                            .normalize()
                            .multiply(f);
                }
            }

            source.sendSuccess(() -> Component.literal("Spawned " + count + " bot(s)"
                    + (inList ? " in the player list" : "")
                    + " with " + armorTier.id() + " armour, " + toolTier.id() + " tools"
                    + (item.isEmpty() ? "" : " and " + item.getHoverName().getString())), true);
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

    /**
     * Points every live bot at one player and selects the PLAYER goal.
     *
     * <p>Ported from {@code settings playertarget}. <b>Divergence:</b> upstream set the target and
     * then told the operator to go and set the goal themselves. Doing it here makes the two
     * targeting commands behave alike, which matters more than keeping upstream's message — two
     * adjacent commands that differ on whether they finish the job are worse than one command
     * that differs from upstream.
     */
    private static int setPlayerTarget(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");

        for (Bot bot : TerminatorPlus.registry().bots()) {
            bot.setTargetPlayer(player.getUUID());
        }

        agent.targeting().setTargetType(TargetGoal.PLAYER);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "All bots now target " + player.getGameProfile().name()
                        + ". Goal set to PLAYER."), true);
        return 1;
    }

    /**
     * Points every live bot at a type or a tag, live.
     *
     * <p>{@code ResourceOrTagArgument} is what makes {@code #minecraft:raiders} work alongside
     * {@code zombie}, and it is why this command needs no add/remove/list family of its own.
     */
    private static int enemyTargetGeneric(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ResourceOrTagArgument.Result<EntityType<?>> result =
                ResourceOrTagArgument.getResourceOrTag(ctx, "type", Registries.ENTITY_TYPE);

        // Expanded to concrete types here rather than kept as the Predicate the Result already
        // is: an expanded set can be reported by /tplus enemytarget and tested without a world,
        // and a tag only changes on a datapack reload anyway.
        Set<EntityType<?>> types = result.unwrap().map(
                ref -> Set.<EntityType<?>>of(ref.value()),
                tag -> tag.stream().map(Holder::value).collect(Collectors.toUnmodifiableSet()));

        if (types.isEmpty()) {
            // An empty tag would set a target that silently matches nothing, which is the failure
            // mode this whole command exists to avoid.
            ctx.getSource().sendFailure(Component.literal(
                    result.asPrintable() + " is empty, so nothing would be targeted."));
            return 0;
        }

        String label = result.asPrintable()
                + (types.size() > 1 ? " (" + types.size() + " types)" : "");

        return applyEnemyTarget(ctx, EnemyTarget.ofTypes(types, label));
    }

    /**
     * Points every live bot at the entities a selector matched, right now.
     *
     * <p>{@code limit=1} and a bare UUID are the same mechanism with a set of one.
     */
    private static int enemyTargetSpecific(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<? extends Entity> selected = EntityArgument.getEntities(ctx, "targets");

        // locateTarget returns a LivingEntity and @e matches boats and item frames. Filtering here
        // rather than at scan time means the operator is told, instead of watching a successful
        // command do nothing.
        List<? extends Entity> living =
                selected.stream().filter(e -> e instanceof LivingEntity).toList();

        if (living.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "None of the " + selected.size() + " selected entities can be targeted."));
            return 0;
        }

        int ignored = selected.size() - living.size();

        if (ignored > 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Ignored " + ignored + " selected entities that cannot be targeted."), false);
        }

        Set<UUID> ids = living.stream().map(Entity::getUUID).collect(Collectors.toUnmodifiableSet());
        String types = living.stream()
                .map(e -> EntityType.getKey(e.getType()).getPath())
                .distinct().sorted().collect(Collectors.joining(", "));

        return applyEnemyTarget(ctx, EnemyTarget.ofEntities(ids,
                living.size() + " entities (" + types + ")"));
    }

    /** Sets the target on every bot and switches the goal to match. */
    private static int applyEnemyTarget(CommandContext<CommandSourceStack> ctx, EnemyTarget target) {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        Collection<Bot> bots = TerminatorPlus.registry().bots();
        bots.forEach(bot -> bot.setEnemyTarget(target));
        agent.targeting().setTargetType(TargetGoal.ENTITY);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Now hunting " + target.label() + " for " + bots.size()
                        + " bot(s). Goal set to ENTITY."), true);
        return 1;
    }

    /**
     * Reports the enemy target, grouped by label.
     *
     * <p>Grouped rather than assumed uniform on purpose: a bot created after the command carries
     * {@code EnemyTarget.NONE}, the same gap {@code /tplus playertarget} has, and this is where an
     * operator finds out — "3 x minecraft:zombie" beside "2 x nothing".
     */
    private static int showEnemyTarget(CommandContext<CommandSourceStack> ctx) {
        Collection<Bot> bots = TerminatorPlus.registry().bots();

        if (bots.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No bots are loaded."), false);
            return 0;
        }

        Map<String, Integer> counts = new TreeMap<>();

        for (Bot bot : bots) {
            counts.merge(bot.getEnemyTarget().label(), 1, Integer::sum);
        }

        String summary = counts.entrySet().stream()
                .map(e -> e.getValue() + " x " + e.getKey())
                .collect(Collectors.joining("\n  "));

        ctx.getSource().sendSuccess(() -> Component.literal("Enemy target:\n  " + summary), false);
        return 1;
    }

    /** Clears the enemy target, leaving the goal alone. */
    private static int clearEnemyTarget(CommandContext<CommandSourceStack> ctx) {
        Collection<Bot> bots = TerminatorPlus.registry().bots();
        bots.forEach(bot -> bot.setEnemyTarget(EnemyTarget.NONE));

        // The goal is deliberately not put back. Setting a target has one right answer for the
        // goal; clearing one does not, and silently re-aiming every bot at the nearest player as a
        // side effect of a clear is worse than a goal that finds nothing until /tplus goal says
        // otherwise.
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared the enemy target for " + bots.size()
                        + " bot(s). The goal is unchanged."), true);
        return 1;
    }

    /**
     * Sets every bot's default item.
     *
     * <p>Ported from {@code give}. The default item is what {@code setItem(null)} restores and
     * what {@code ItemUtils} scores for damage, so this is how a bot is armed.
     */
    private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ItemStack stack = ItemArgument.getItem(ctx, "item").createItemStack(1);
        Collection<Bot> bots = TerminatorPlus.registry().bots();

        for (Bot bot : bots) {
            bot.setDefaultItem(stack.copy());

            // Not upstream's: it set the field and left a bot holding whatever it had until the
            // next resetHand. Putting the item in hand now is what an operator typing the
            // command expects, and resetHand would do it within a few ticks anyway.
            bot.setItem(null);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set the default item to " + stack.getHoverName().getString()
                        + " for " + bots.size() + " bot(s)"), true);
        return 1;
    }

    /**
     * Equips every bot with an armour tier.
     *
     * <p>Ported from {@code armor}. The four-piece loop moved to {@code EquipmentTier.equipArmor}
     * so the index-to-slot pairing sits beside the table it indexes.
     */
    private static int armor(CommandContext<CommandSourceStack> ctx) {
        EquipmentTier tier = tier(ctx.getSource(), StringArgumentType.getString(ctx, "tier"), true);

        if (tier == null) {
            return 0;
        }

        Collection<Bot> bots = TerminatorPlus.registry().bots();
        bots.forEach(tier::equipArmor);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set armour tier '" + tier.id() + "' for " + bots.size() + " bot(s)"), true);
        return 1;
    }

    /**
     * Sets every bot's tool tier.
     *
     * <p>New: upstream had no equivalent, because its tool list was static. Without this, tools
     * would be the only one of the three equipment properties that can be set at spawn and never
     * changed afterwards.
     *
     * <p>This is what decides how fast a bot breaks a block: {@code Mining.blockBreakEffect}
     * accrues the held tool's destroy speed. Block hardness is still ignored, as upstream ignored
     * it. {@code none} floors at wood — there is no bare-handed tier.
     */
    private static int tools(CommandContext<CommandSourceStack> ctx) {
        EquipmentTier requested = tier(ctx.getSource(),
                StringArgumentType.getString(ctx, "tier"), false);

        if (requested == null) {
            return 0;
        }

        // Resolved here as well as inside setToolTier so the message names the tier the bots
        // actually got. asToolTier is the single definition of the floor, so the two cannot
        // disagree.
        EquipmentTier applied = requested.asToolTier();

        Collection<Bot> bots = TerminatorPlus.registry().bots();
        bots.forEach(bot -> bot.setToolTier(applied));

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set tool tier '" + applied.id() + "' for " + bots.size() + " bot(s)"
                        + (applied == requested ? "" : " ('none' floors at wood)")), true);
        return 1;
    }

    /**
     * Reports one bot's state.
     *
     * <p>Ported from {@code info}. Upstream ran this asynchronously and wrapped it in a
     * catch-all because it also did a name lookup that could block; ours reads live entity state
     * and must therefore run on the server thread. Upstream's own comment lists fields it never
     * implemented — creation time, inventory, current target, skin — and those stay
     * unimplemented here.
     */
    private static int info(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Bot bot = TerminatorPlus.registry().byName(name);

        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("No bot named '" + name + "'"));
            return 0;
        }

        Vec3 pos = bot.position();
        MotionVec vel = bot.getVelocity();

        ctx.getSource().sendSuccess(() -> Component.literal(bot.getBotName())
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(
                        "\n  Level: " + bot.level().dimension().identifier()
                        + "\n  Position: " + BotMath.round2Dec(pos.x) + ", "
                                + BotMath.round2Dec(pos.y) + ", " + BotMath.round2Dec(pos.z)
                        + "\n  Velocity: " + BotMath.round2Dec(vel.getX()) + ", "
                                + BotMath.round2Dec(vel.getY()) + ", " + BotMath.round2Dec(vel.getZ())
                        + "\n  Health: " + BotMath.round1Dec(bot.getBotHealth())
                                + " / " + BotMath.round1Dec(bot.getBotMaxHealth())
                        + "\n  Alive ticks: " + bot.getAliveTicks()
                        + "\n  Kills: " + bot.getKills()
                        + "\n  In player list: " + bot.isInPlayerList()
                        + "\n  Skin: " + describeSkin(bot))
                        .withStyle(ChatFormatting.RESET)), false);
        return 1;
    }

    /**
     * Whether a bot's profile carries a texture, and whether it is signed.
     *
     * <p>Added while chasing skins that never rendered. A bot showing "none" never got one from
     * Mojang; one showing "signed" has a texture a modern client will reject, because the
     * signature is bound to the original account's profile id and a bot's id is fresh.
     */
    private static String describeSkin(Bot bot) {
        var textures = bot.getGameProfile().properties().get("textures");

        if (textures.isEmpty()) {
            return "none";
        }

        var property = textures.iterator().next();

        return (property.signature() == null ? "unsigned" : "signed")
                + ", " + property.value().length() + " chars";
    }

    /**
     * Confines or biases bots to a box.
     *
     * <p>With all three weights at zero the box is a hard boundary — a target outside it is not
     * a candidate at all. With non-zero weights it is a bias instead: a target outside is
     * penalised by the weight times its squared distance out along that axis, so bots prefer
     * what is inside the box without ignoring what is not. See {@code Targeting}.
     */
    private static int setRegion(CommandContext<CommandSourceStack> ctx,
                                 double weightX, double weightY, double weightZ)
            throws CommandSyntaxException {
        LegacyAgent agent = legacyAgent(ctx);

        if (agent == null) {
            return 0;
        }

        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");

        // encapsulatingFullBlocks covers both blocks entirely, which is what an operator who
        // selected two corners means. An AABB built from the raw positions would stop at their
        // minimum corners and be one block short in each axis.
        AABB region = AABB.encapsulatingFullBlocks(from, to);
        boolean hard = weightX == 0 && weightY == 0 && weightZ == 0;

        agent.targeting().setRegion(region, weightX, weightY, weightZ);

        ctx.getSource().sendSuccess(() -> Component.literal("Region set to "
                + describe(region) + (hard ? " (hard boundary)" : " (weighted)")), true);
        return 1;
    }

    /**
     * Reports the current region.
     *
     * <p>Upstream's {@code /bot settings region} with no arguments. Its absence here was
     * invisible until a review noticed that {@code Targeting.getRegion} and the three weight
     * accessors had no callers at all — which is what a missing report command looks like from
     * the inside. {@code goal} and {@code mobtarget} both have the same no-argument form.
     */
    private static int showRegion(CommandContext<CommandSourceStack> ctx) {
        LegacyAgent agent = legacyAgent(ctx);

        if (agent == null) {
            return 0;
        }

        AABB region = agent.targeting().getRegion();

        if (region == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No region is set."), false);
            return 1;
        }

        double wx = agent.targeting().getRegionWeightX();
        double wy = agent.targeting().getRegionWeightY();
        double wz = agent.targeting().getRegionWeightZ();

        String detail = wx == 0 && wy == 0 && wz == 0
                ? "\n  Entities outside it are not targeted at all."
                : "\n  Weights: " + wx + ", " + wy + ", " + wz;

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Region: " + describe(region) + detail), false);
        return 1;
    }

    private static int clearRegion(CommandContext<CommandSourceStack> ctx) {
        LegacyAgent agent = legacyAgent(ctx);

        if (agent == null) {
            return 0;
        }

        agent.targeting().setRegion(null, 0, 0, 0);
        ctx.getSource().sendSuccess(() -> Component.literal("Region cleared"), true);
        return 1;
    }

    /**
     * The box as the two blocks an operator selected.
     *
     * <p>The maxima are one less than the AABB's, because a box that covers block 32 entirely
     * ends at 33.0. Printing the raw bound tells an operator who typed 32 that they got 33.
     */
    private static String describe(AABB region) {
        return "[" + (int) region.minX + ", " + (int) region.minY + ", " + (int) region.minZ
                + "] to [" + ((int) region.maxX - 1) + ", " + ((int) region.maxY - 1)
                + ", " + ((int) region.maxZ - 1) + "]";
    }

    /** The installed agent, or null with a message already sent to the source. */
    /**
     * Reports the descent range.
     *
     * <p>A range of 0 is a real setting rather than a degenerate one: {@code horizontal < 0}
     * never holds, so stuck bots stop descending entirely and only bots already beside their
     * target dig down. It is the far end of the same dial and needs no special case.
     */
    private static int showDescendRange(CommandContext<CommandSourceStack> ctx) {
        LegacyAgent agent = legacyAgent(ctx);

        if (agent == null) {
            return 0;
        }

        int range = agent.descendRange;
        String text = range == LegacyAgent.DESCEND_RANGE_UNLIMITED
                ? "Descent range is unlimited — bots tunnel down toward a target at any distance."
                : "Descent range is " + range + " blocks.";

        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int setDescendRange(CommandContext<CommandSourceStack> ctx, int range) {
        LegacyAgent agent = legacyAgent(ctx);

        if (agent == null) {
            return 0;
        }

        agent.descendRange = range;

        String text = range == LegacyAgent.DESCEND_RANGE_UNLIMITED
                ? "Descent range set to unlimited"
                : "Descent range set to " + range + " blocks";

        ctx.getSource().sendSuccess(() -> Component.literal(text), true);
        return 1;
    }

    private static LegacyAgent legacyAgent(CommandContext<CommandSourceStack> ctx) {
        if (TerminatorPlus.registry().agent() instanceof LegacyAgent agent) {
            return agent;
        }

        ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
        return null;
    }

    /**
     * Why this command exists.
     *
     * <p>Ported from {@code help}. Both bodies are upstream's text, reflowed, plus one sentence
     * upstream should have had: neither list survives a restart.
     */
    private static int environmentHelp(CommandContext<CommandSourceStack> ctx, String topic) {
        String body = switch (topic) {
            case "blocks" -> """
                    Blocks added by mods are not solid as far as vanilla is concerned, so bots
                    walk into them, place water against them, and fail to stand on them.
                      /tplus environment addsolid <block> declares one solid.
                      /tplus environment addsolid at <pos> declares whatever is at that position.
                    The list is not saved and does not survive a restart.""";
            case "mobs" -> """
                    The custom mob list is an operator-defined list of entity types.
                      /tplus environment addmob <type> adds one.
                      /tplus environment moblisttype changes what the list is for: CUSTOM makes
                      it the whole of the CUSTOM_LIST goal, and HOSTILE, RAIDER or MOB appends it
                      to that goal's built-in set.
                    The list is not saved and does not survive a restart.""";
            default -> """
                    /tplus environment help blocks - declaring modded blocks solid.
                    /tplus environment help mobs - building the custom mob list.""";
        };

        ctx.getSource().sendSuccess(() -> Component.literal(body), false);
        return 1;
    }

    /**
     * The block at a position, for an operator who can see the thing but not its id.
     *
     * <p>Ported from {@code getMaterial}. Upstream parsed three coordinates itself, handled
     * {@code ~} itself, and checked the chunk itself; {@code getLoadedBlockPos} does all three and
     * fails with vanilla's own message.
     */
    private static int getBlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        Block block = ctx.getSource().getLevel().getBlockState(pos).getBlock();

        ctx.getSource().sendSuccess(() -> Component.literal(
                        "Block at [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]: ")
                .append(Component.literal(id(block)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(BlockRules.isSolid(block.defaultBlockState())
                        ? " (solid)" : " (not solid)")), false);
        return 1;
    }

    /**
     * Declares a block solid, or stops declaring it.
     *
     * <p>Ported from {@code addSolid} and {@code removeSolid}, which upstream wrote as forty
     * near-identical lines twice. The only differences were the set method and three message
     * strings, so this takes the direction as a parameter.
     */
    private static int setSolid(CommandContext<CommandSourceStack> ctx, Block block, boolean add) {
        boolean changed = add ? BlockRules.addSolid(block) : BlockRules.removeSolid(block);

        if (!changed) {
            ctx.getSource().sendFailure(Component.literal(id(block)
                    + (add ? " is already in the solid list." : " is not in the solid list.")));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                (add ? "Added " : "Removed ") + id(block)
                        + (add ? " to" : " from") + " the solid list."), true);
        return 1;
    }

    private static int listSolids(CommandContext<CommandSourceStack> ctx) {
        Set<Block> blocks = BlockRules.solidOverrides();

        if (blocks.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No blocks have been declared solid."), false);
            return 1;
        }

        String body = blocks.stream().map(BotCommands::id).sorted()
                .collect(Collectors.joining("\n  "));

        ctx.getSource().sendSuccess(() -> Component.literal(
                blocks.size() + " block(s) declared solid:\n  " + body), false);
        return 1;
    }

    private static int clearSolids(CommandContext<CommandSourceStack> ctx) {
        int size = BlockRules.clearSolidOverrides();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared " + size + " block(s) from the solid list."), true);
        return 1;
    }

    private static Block blockArgument(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ResourceArgument.getResource(ctx, "block", Registries.BLOCK).value();
    }

    private static Block blockAtPosition(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        return ctx.getSource().getLevel().getBlockState(pos).getBlock();
    }

    /** The registry id, which is what an operator needs for a modded block. */
    private static String id(Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? block.getName().getString() : key.toString();
    }

    /**
     * Adds a mob type to the custom list, or takes one out.
     *
     * <p>Ported from {@code addCustomMob} and {@code removeCustomMob}, folded the same way
     * {@link #setSolid} folds its pair.
     *
     * <p>What the list is <i>for</i> depends on {@code moblisttype}: with CUSTOM it is the whole
     * of the CUSTOM_LIST goal, and with HOSTILE, RAIDER or MOB it is appended to that goal's
     * built-in set. Four of the eleven goals read it.
     */
    private static int setMob(CommandContext<CommandSourceStack> ctx, boolean add)
            throws CommandSyntaxException {
        EntityType<?> type = ResourceArgument.getResource(ctx, "type", Registries.ENTITY_TYPE).value();
        String name = EntityType.getKey(type).toString();

        boolean changed = add
                ? Targeting.CUSTOM_MOB_LIST.add(type)
                : Targeting.CUSTOM_MOB_LIST.remove(type);

        if (!changed) {
            ctx.getSource().sendFailure(Component.literal(name
                    + (add ? " is already in the custom mob list." : " is not in the custom mob list.")));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                (add ? "Added " : "Removed ") + name
                        + (add ? " to" : " from") + " the custom mob list."), true);
        return 1;
    }

    private static int listMobs(CommandContext<CommandSourceStack> ctx) {
        Set<EntityType<?>> types = Targeting.CUSTOM_MOB_LIST;

        if (types.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "The custom mob list is empty. The CUSTOM_LIST goal will find nothing."), false);
            return 1;
        }

        String body = types.stream().map(t -> EntityType.getKey(t).toString()).sorted()
                .collect(Collectors.joining("\n  "));

        ctx.getSource().sendSuccess(() -> Component.literal(
                types.size() + " mob type(s), mode " + Targeting.customListMode
                        + ":\n  " + body), false);
        return 1;
    }

    private static int clearMobs(CommandContext<CommandSourceStack> ctx) {
        int size = Targeting.CUSTOM_MOB_LIST.size();
        Targeting.CUSTOM_MOB_LIST.clear();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared " + size + " mob type(s) from the custom list."), true);
        return 1;
    }

    private static int showMobListType(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "The custom mob list mode is " + Targeting.customListMode
                        + ". Available: " + CustomListMode.listModes()), false);
        return 1;
    }

    private static int setMobListType(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "mode");
        CustomListMode mode = CustomListMode.from(name);

        if (mode == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + name + "' is not a mode. Available: " + CustomListMode.listModes()));
            return 0;
        }

        Targeting.customListMode = mode;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Custom mob list mode is now " + mode + "."), true);
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
