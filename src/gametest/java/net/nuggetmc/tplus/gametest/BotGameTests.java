package net.nuggetmc.tplus.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotConnection;
import net.nuggetmc.tplus.bot.BotRegistry;

/**
 * In-world tests for the bot lifecycle.
 *
 * <p>These were originally written as JUnit tests against
 * {@code EphemeralTestServerProvider}, which turned out to have no levels at all — its
 * provider builds a frozen, empty {@code LevelStem} registry. Anything that constructs a
 * {@code Bot} needs a real {@code ServerLevel}, so it has to run here.
 *
 * <p>Spec risk 1 — bots inside the real {@code PlayerList} — is what this suite exists
 * to settle, along with everything tag- and shape-dependent that a bootstrapped JUnit
 * environment cannot reach.
 *
 * <p>Positions are relative to the test structure; {@code helper.absolutePos} converts.
 * {@code @EmptyTemplate(floor = true)} synthesises the structure, so no {@code .nbt}
 * files are needed.
 */
@ForEachTest(groups = BotGameTests.GROUP)
public final class BotGameTests {

    public static final String GROUP = "bot.lifecycle";

    private BotGameTests() {
    }

    // ---- helpers -----------------------------------------------------------

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, BlockPos relative, String name) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relative));

        return BotFactory.spawn(registry, level, pos, 0f, 0f,
                BotGameProfiles.create(name, null), false);
    }

    private static void tickBot(Bot bot, int ticks) {
        for (int i = 0; i < ticks; i++) {
            bot.tick();
        }
    }

    private static void tickUntilGrounded(Bot bot, int maxTicks) {
        for (int i = 0; i < maxTicks && !bot.isBotOnGround(); i++) {
            bot.tick();
        }
    }

    /**
     * Forces SURVIVAL.
     *
     * <p>ServerPlayer's constructor calls calculateGameModeForNewPlayer, so a bot
     * inherits the server's default game type. The GameTest level is created with
     * GameType.CREATIVE, which makes bots immune to damage — any damage assertion in this
     * world is vacuous without this. Production is unaffected: a survival server gives
     * survival bots, which is what upstream relied on too.
     */
    private static void makeSurvival(Bot bot) {
        bot.setGameMode(GameType.SURVIVAL);
    }

    /** Places a block with flag 2: notify clients, skip the neighbour update. */
    private static void place(ExtendedGameTestHelper helper, BlockPos relative, BlockState state) {
        helper.getLevel().setBlock(helper.absolutePos(relative), state, 2);
    }

    // ---- spawn paths -------------------------------------------------------

    @GameTest
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_spawns_as_fresh_entity")
    static void botSpawnsAsAFreshEntityWithoutJoiningThePlayerList(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "FreshBot");

        helper.assertTrue(bot.isAlive(), "bot should be alive");
        helper.assertFalse(bot.isInPlayerList(), "bot must not be in the player list");
        helper.assertFalse(helper.getLevel().getServer().getPlayerList().getPlayers().contains(bot),
                "server player list must not contain the bot");
        helper.assertTrue(helper.getLevel().getEntity(bot.getId()) == bot,
                "bot must be a real entity in the level");

        bot.removeBot();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_joins_the_player_list")
    static void botJoinsTheRealPlayerList(ExtendedGameTestHelper helper) {
        // Spec risk 1, settled the other way. Plan A proved the Paper build's
        // getPlayers().add(bot) is impossible — NeoForge narrowed getPlayers() to an
        // unmodifiable view on purpose — and deferred the real route to here.
        BotRegistry registry = new BotRegistry();
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();

        int before = server.getPlayerList().getPlayerCount();

        Bot bot = BotFactory.spawn(registry, level,
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))), 0f, 0f,
                BotGameProfiles.create("ListBot", null), true);

        // The whole point of the feature: the server counts the bot as an online player.
        helper.assertValueEqual(server.getPlayerList().getPlayerCount(), before + 1,
                "player count after a playerlist spawn");
        helper.assertTrue(server.getPlayerList().getPlayers().contains(bot),
                "the bot must be in the player list");
        helper.assertTrue(bot.isInPlayerList(), "the bot must know it is in the list");

        bot.removeBot();

        helper.assertValueEqual(server.getPlayerList().getPlayerCount(), before,
                "removeBot must take the bot back out of the list");
        helper.assertFalse(server.getPlayerList().getPlayers().contains(bot),
                "the bot must be gone from the player list");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("playerlist_spawn_is_silent")
    static void aPlayerListSpawnDoesNotAnnounceAJoin(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        ServerLevel level = helper.getLevel();

        // Upstream's insert was silent. placeNewPlayer would broadcast "ListBot joined the
        // game" to every player, fire PlayerLoggedInEvent, sync datapacks and teleport — none
        // of which upstream did, and the join message alone makes spawning a hundred bots
        // unusable. There is no clean hook to assert the absence of a broadcast, so this
        // asserts the thing that proves placeNewPlayer was not used: the bot still has the
        // fake connection the factory gave it, not a fresh one built around a real Connection.
        Bot bot = BotFactory.spawn(registry, level,
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))), 0f, 0f,
                BotGameProfiles.create("QuietBot", null), true);

        helper.assertTrue(bot.connection != null, "the bot keeps a packet listener");
        helper.assertTrue(bot.connection.getConnection() instanceof BotConnection,
                "the bot's connection must still be BotConnection");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_connection_has_a_netty_channel")
    static void botConnectionHasANettyChannel(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "ChannelBot");

        // This looks like an implementation detail and is not. On a dedicated server
        // NeoForge's ConfigSync.syncPendingConfigs runs every ServerTickEvent.Post, walks the
        // PlayerList and calls hasChannel on each player's listener, which reads a netty
        // attribute off this channel. With a null channel that is an NPE in the server tick —
        // it crashed a real server on the first tick after a playerlist bot spawned.
        //
        // The GameTest server does not run that path, so no in-world test can reproduce the
        // crash. This pins the invariant the crash depended on instead.
        helper.assertTrue(bot.connection.getConnection().channel() != null,
                "BotConnection must have a netty channel, or NeoForge's per-tick config sync "
                        + "NPEs on any bot in the PlayerList");

        bot.removeBot();
        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("playerlist_bot_survives_a_server_tick")
    static void aPlayerListBotSurvivesAServerTick(ExtendedGameTestHelper helper) {
        // The open question this task exists to answer: does vanilla tolerate a ServerPlayer
        // in PlayerList.players whose connection goes nowhere? Every broadcast the server makes
        // iterates that list. BotConnection swallows sends, so it should — but "should" is why
        // this test is here rather than a comment.
        BotRegistry registry = new BotRegistry();
        MinecraftServer server = helper.getLevel().getServer();

        Bot bot = BotFactory.spawn(registry, helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))), 0f, 0f,
                BotGameProfiles.create("TickBot", null), true);

        for (int i = 0; i < 20; i++) {
            bot.tick();
        }

        // Something that broadcasts to every player in the list, which is where a bot with a
        // dead connection would blow up if BotConnection were not swallowing sends.
        server.getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal("tick probe"), false);

        helper.assertTrue(bot.isAlive(), "a playerlist bot must survive being ticked");
        helper.assertTrue(server.getPlayerList().getPlayers().contains(bot),
                "and must still be in the list");

        bot.removeBot();
        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_spawn_sets_registry_owner")
    static void spawningRegistersTheBotAndSetsItsOwner(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "OwnedBot");

        helper.assertValueEqual(registry.size(), 1, "registry size after spawn");
        helper.assertTrue(bot.getRegistry() == registry,
                "bot must point at the registry that owns it, not a global one");

        bot.removeBot();
        helper.succeed();
    }

    // ---- the fake connection ----------------------------------------------

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_ticks_through_fake_connection")
    static void tickingDoesNotThrowThroughTheFakeConnection(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 3, 2), "TickBot");

        tickBot(bot, 40);

        helper.assertTrue(bot.isAlive(), "bot should survive 40 ticks");
        helper.assertValueEqual(registry.size(), 1, "bot should not have been evicted");

        bot.removeBot();
        helper.succeed();
    }

    // ---- physics -----------------------------------------------------------

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x8x5", floor = true)
    @TestHolder("bot_lands_on_floor")
    static void botLandsOnTheFloor(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 6, 2), "FallBot");
        double startY = bot.getY();

        tickUntilGrounded(bot, 100);

        helper.assertTrue(bot.getY() < startY, "bot should have fallen from y=" + startY);
        helper.assertTrue(bot.isBotOnGround(), "bot never landed; y=" + bot.getY());
        helper.assertFalse(bot.getStandingOn().isEmpty(),
                "standingOn was empty while the bot was on the ground");

        bot.removeBot();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x8x5", floor = true)
    @TestHolder("bot_stands_on_fence")
    static void botStandsOnAFence(ExtendedGameTestHelper helper) {
        // Exercises the BlockTags.FENCES branch of GroundCheck via typeHolder().is(...),
        // which needs a loaded datapack and so cannot be unit tested.
        place(helper, new BlockPos(2, 1, 2), Blocks.OAK_FENCE.defaultBlockState());

        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 6, 2), "FenceBot");

        tickUntilGrounded(bot, 100);

        helper.assertTrue(bot.isBotOnGround(), "bot did not come to rest; y=" + bot.getY());
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x8x5", floor = true)
    @TestHolder("bot_stands_on_glass_pane")
    static void botStandsOnAGlassPane(ExtendedGameTestHelper helper) {
        // LegacyMats.FENCE included GLASS_PANE and IRON_BARS, which is why the fence pass
        // consults BlockTags.BARS. Regression guard for that being dropped.
        place(helper, new BlockPos(2, 1, 2), Blocks.GLASS_PANE.defaultBlockState());

        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 6, 2), "PaneBot");

        tickUntilGrounded(bot, 100);

        helper.assertTrue(bot.isBotOnGround(), "bot did not come to rest on the pane; y=" + bot.getY());
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x8x5", floor = true)
    @TestHolder("bot_ignores_no_collision_blocks")
    static void botDoesNotTreatFlowersAsGround(ExtendedGameTestHelper helper) {
        // GroundCheck keys off the COLLISION shape, not the outline. Measured in 26.2,
        // eleven blocks have an empty collision shape but a non-empty outline - grass,
        // flowers, torches, rails, pressure plates among them - and none of those are
        // standable upstream. Keying off the outline shape would make a bot think a poppy
        // was solid ground. This pins that choice.
        BlockPos flower = new BlockPos(2, 1, 2);
        place(helper, flower, Blocks.POPPY.defaultBlockState());

        BlockState placed = helper.getLevel().getBlockState(helper.absolutePos(flower));
        helper.assertTrue(placed.getCollisionShape(helper.getLevel(), helper.absolutePos(flower)).isEmpty(),
                "a poppy is expected to have an EMPTY collision shape");
        helper.assertFalse(placed.getShape(helper.getLevel(), helper.absolutePos(flower)).isEmpty(),
                "a poppy is expected to have a NON-EMPTY outline shape");

        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 6, 2), "FlowerBot");

        tickUntilGrounded(bot, 100);

        helper.assertTrue(bot.isBotOnGround(), "bot should rest on the floor; y=" + bot.getY());
        helper.assertFalse(bot.getStandingOn().contains(helper.absolutePos(flower)),
                "the poppy must NOT count as ground - GroundCheck is reading the outline "
                        + "shape instead of the collision shape");

        bot.removeBot();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "5x8x5", floor = true)
    @TestHolder("bot_takes_fall_damage")
    static void botTakesFallDamageOnceTheGracePeriodHasElapsed(ExtendedGameTestHelper helper) {
        // noFallTicks starts at 60 and decrements once per tick, so a freshly spawned bot
        // is immune for its first 60 ticks. Burn that off on the ground first, otherwise
        // this test silently proves nothing.
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "HurtBot");
        makeSurvival(bot);

        tickBot(bot, 70);

        float healthBefore = bot.getHealth();
        BlockPos high = helper.absolutePos(new BlockPos(2, 2, 2)).above(60);
        bot.setPos(high.getX() + 0.5, high.getY(), high.getZ() + 0.5);

        for (int i = 0; i < 300 && bot.getHealth() >= healthBefore; i++) {
            bot.tick();
        }

        helper.assertTrue(bot.getHealth() < healthBefore,
                "bot took no fall damage: " + healthBefore + " -> " + bot.getHealth());

        bot.removeBot();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "5x8x5", floor = true)
    @TestHolder("bot_no_fall_damage_on_cobweb")
    static void botTakesNoFallDamageLandingOnACobweb(ExtendedGameTestHelper helper) {
        // isFallBlocked cancels fall damage for BotUtils.NO_FALL blocks. That guard was
        // missing entirely until the second review pass caught it.
        //
        // Cobweb rather than water on purpose: water applies buoyancy, which lifts the bot
        // out of the column during the grace-period warmup and makes the test
        // non-deterministic. Cobweb is in the same NO_FALL set and exercises the same
        // branch without moving the bot.
        BlockPos web = new BlockPos(2, 1, 2);
        place(helper, web, Blocks.COBWEB.defaultBlockState());

        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "WebBot");
        makeSurvival(bot);

        // Burn off the 60-tick noFallTicks grace period, or this proves nothing.
        tickBot(bot, 70);

        float healthBefore = bot.getHealth();
        BlockPos high = helper.absolutePos(web).above(60);
        bot.setPos(high.getX() + 0.5, high.getY(), high.getZ() + 0.5);

        tickUntilGrounded(bot, 300);

        helper.assertTrue(bot.isBotOnGround(), "bot never landed; y=" + bot.getY());
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(web)).getBlock() == Blocks.COBWEB,
                "the cobweb should still be there");
        helper.assertTrue(bot.getHealth() >= healthBefore,
                "a NO_FALL block must cancel fall damage (isFallBlocked): "
                        + healthBefore + " -> " + bot.getHealth());

        bot.removeBot();
        helper.succeed();
    }

    // ---- death -------------------------------------------------------------

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_death_removes_after_delay")
    static void deathUnregistersImmediatelyAndDiscardsAfterTwentyTicks(ExtendedGameTestHelper helper) {
        // die() was rewritten during plan review because the first version only sent
        // despawn packets, leaking a dead entity per death. Nothing exercised it until
        // now. Upstream's dieCheck unregisters and hides at once, then discards the entity
        // 20 ticks later so the death animation can play.
        //
        // The delayed half runs on the owning registry's scheduler, so this test has to
        // tick that registry - a registry nobody ticks would never fire it.
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "DyingBot");
        makeSurvival(bot);

        // A fresh ServerPlayer carries invulnerableTime = 60, decremented in
        // ServerPlayer.tick(), so a lethal hit before this is simply ignored.
        tickBot(bot, 70);

        int id = bot.getId();
        helper.assertValueEqual(registry.size(), 1, "registry size after spawn");

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().genericKill(), 1000f);

        helper.assertFalse(bot.isAlive(), "bot should be dead after a lethal hit");
        helper.assertValueEqual(registry.size(), 0, "die() should unregister the bot at once");
        helper.assertTrue(helper.getLevel().getEntity(id) == bot,
                "the entity should still be present during the 20-tick death delay");

        for (int i = 0; i < 19; i++) {
            registry.scheduler().tick();
        }
        helper.assertTrue(helper.getLevel().getEntity(id) == bot,
                "the entity must survive until the delay elapses");

        registry.scheduler().tick();

        helper.assertTrue(bot.isRemoved(), "bot should be removed once the delay elapses");
        helper.assertTrue(helper.getLevel().getEntity(id) == null,
                "the entity must be gone from the level after the delayed removeBot");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_death_respects_remove_on_death")
    static void deathLeavesTheBotAloneWhenRemoveOnDeathIsOff(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "StayingBot");
        makeSurvival(bot);
        bot.setRemoveOnDeath(false);

        // Burn off spawn invulnerability (see the sibling test).
        tickBot(bot, 70);

        int id = bot.getId();
        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().genericKill(), 1000f);

        helper.assertFalse(bot.isAlive(), "bot should still die");
        helper.assertValueEqual(registry.size(), 1, "removeOnDeath=false must keep it registered");

        for (int i = 0; i < 40; i++) {
            registry.scheduler().tick();
        }

        helper.assertTrue(helper.getLevel().getEntity(id) == bot,
                "removeOnDeath=false must leave the entity in the world");

        bot.removeBot();
        helper.succeed();
    }

    // ---- identity and cleanup ---------------------------------------------

    @GameTest
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_is_not_a_fake_player")
    static void botsDoNotReportThemselvesAsFakePlayers(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "RealBot");

        helper.assertFalse(bot.isFakePlayer(),
                "spec risk 4: bots must look like real players to other mods");

        bot.removeBot();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_removal_clears_world")
    static void removalTakesTheBotOutOfTheWorld(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "GoneBot");
        int id = bot.getId();

        bot.removeBot();

        helper.assertTrue(bot.isRemoved(), "bot should be marked removed");
        helper.assertTrue(helper.getLevel().getEntity(id) == null,
                "the entity must be gone from the level after removeBot");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("registry_evicts_removed_bots")
    static void registryEvictsRemovedBotsOnTick(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 2), "EvictBot");

        helper.assertValueEqual(registry.size(), 1, "registry size after spawn");

        bot.removeBot();
        registry.tick();

        helper.assertValueEqual(registry.size(), 0, "registry must drop bots removed from the world");
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x6x7", floor = true)
    @TestHolder("registry_reset_removes_all")
    static void resetRemovesEveryBot(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        for (int i = 0; i < 3; i++) {
            spawn(helper, registry, new BlockPos(1 + i * 2, 2, 3), "ResetBot" + i);
        }

        helper.assertValueEqual(registry.size(), 3, "registry size after three spawns");

        registry.reset();

        helper.assertValueEqual(registry.size(), 0, "reset must clear the registry");
        helper.succeed();
    }
}
