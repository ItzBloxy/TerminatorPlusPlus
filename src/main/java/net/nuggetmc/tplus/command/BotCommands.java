package net.nuggetmc.tplus.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
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
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.motion.MotionVec;
import net.nuggetmc.tplus.util.MojangSkins;

import java.util.Collection;
import java.util.Map;
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
    private static final Map<String, Item[]> ARMOR_TIERS = Map.of(
            "none", new Item[]{null, null, null, null},
            "leather", new Item[]{Items.LEATHER_BOOTS, Items.LEATHER_LEGGINGS,
                    Items.LEATHER_CHESTPLATE, Items.LEATHER_HELMET},
            "chain", new Item[]{Items.CHAINMAIL_BOOTS, Items.CHAINMAIL_LEGGINGS,
                    Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_HELMET},
            "gold", new Item[]{Items.GOLDEN_BOOTS, Items.GOLDEN_LEGGINGS,
                    Items.GOLDEN_CHESTPLATE, Items.GOLDEN_HELMET},
            "iron", new Item[]{Items.IRON_BOOTS, Items.IRON_LEGGINGS,
                    Items.IRON_CHESTPLATE, Items.IRON_HELMET},
            "diamond", new Item[]{Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS,
                    Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET},
            "netherite", new Item[]{Items.NETHERITE_BOOTS, Items.NETHERITE_LEGGINGS,
                    Items.NETHERITE_CHESTPLATE, Items.NETHERITE_HELMET});

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

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

        root.then(Commands.literal("give")
                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                        .executes(BotCommands::give)));

        root.then(Commands.literal("armor")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ARMOR_TIERS.keySet().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(BotCommands::armor)));

        root.then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (Bot bot : TerminatorPlus.registry().bots()) {
                                builder.suggest(bot.getBotName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommands::info)));

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

    /**
     * Points every live bot at one player.
     *
     * <p>Ported from {@code settings playertarget}. Upstream's message said it plainly and it is
     * worth repeating to the operator: the PLAYER goal has to be selected separately, or this
     * changes nothing.
     */
    private static int setPlayerTarget(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");

        for (Bot bot : TerminatorPlus.registry().bots()) {
            bot.setTargetPlayer(player.getUUID());
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "All bots now target " + player.getGameProfile().name()
                        + ". Set the goal to 'player' for this to take effect."), true);
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
     * Equips every bot with an armor tier.
     *
     * <p>Ported from {@code armor}. Upstream wrote the Bukkit inventory <i>and</i> sent the
     * equipment packets, with the comment "packet sending to ensure";
     * {@code Bot.setItem(stack, slot)} already does both, so one call per slot is enough.
     */
    private static int armor(CommandContext<CommandSourceStack> ctx) {
        String tier = StringArgumentType.getString(ctx, "tier").toLowerCase();
        Item[] pieces = ARMOR_TIERS.get(tier);

        if (pieces == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + tier + "' is not a valid tier. Available: "
                            + ARMOR_TIERS.keySet().stream().sorted().collect(Collectors.joining(", "))));
            return 0;
        }

        Collection<Bot> bots = TerminatorPlus.registry().bots();

        for (Bot bot : bots) {
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                ItemStack stack = pieces[i] == null ? ItemStack.EMPTY : new ItemStack(pieces[i]);
                bot.setItem(stack, ARMOR_SLOTS[i]);
            }
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set armor tier '" + tier + "' for " + bots.size() + " bot(s)"), true);
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
                        + "\n  In player list: " + bot.isInPlayerList())
                        .withStyle(ChatFormatting.RESET)), false);
        return 1;
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
    private static LegacyAgent legacyAgent(CommandContext<CommandSourceStack> ctx) {
        if (TerminatorPlus.registry().agent() instanceof LegacyAgent agent) {
            return agent;
        }

        ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
        return null;
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
