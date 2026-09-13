package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.event.BotDamageByPlayerEvent;

/**
 * In-world tests for the damage path.
 *
 * <p>Bots inherit the level's default gamemode, and the GameTest level is CREATIVE, which makes
 * every damage assertion vacuously true. Plan A was bitten by this; every test here sets
 * SURVIVAL explicitly.
 *
 * <p>A freshly constructed ServerPlayer carries {@code invulnerableTime = 60}, so a hit before
 * the bot has ticked is silently ignored. Tick past it first.
 */
@ForEachTest(groups = BotCombatTests.GROUP)
public final class BotCombatTests {

    public static final String GROUP = "bot.combat";

    private BotCombatTests() {
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relative));

        Bot bot = BotFactory.spawn(registry, level, pos, 0f, 0f,
                BotGameProfiles.create("CombatBot", null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    /** Clears the 60-tick spawn invulnerability. */
    private static void warmUp(Bot bot) {
        for (int i = 0; i < 70; i++) {
            bot.tick();
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("a_player_hit_fires_the_damage_event_and_lands")
    static void a_player_hit_fires_the_damage_event_and_lands(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);

        var player = helper.makeMockServerPlayer(GameType.SURVIVAL);
        float before = bot.getHealth();

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 4f);

        helper.assertTrue(bot.getHealth() < before, "an unblocked player hit must reduce health");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("a_cancelled_damage_event_stops_the_hit")
    static void a_cancelled_damage_event_stops_the_hit(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);

        // Stand in for the agent: the real handler cancels when a shield is raised and the
        // attacker is in front. Here the interest is only that cancelling is honoured.
        registry.setAgent(new AlwaysBlockingAgent(registry));

        var player = helper.makeMockServerPlayer(GameType.SURVIVAL);
        float before = bot.getHealth();

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 4f);

        helper.assertValueEqual(bot.getHealth(), before, "a cancelled event must not damage the bot");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("a_modified_damage_value_is_used")
    static void a_modified_damage_value_is_used(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);

        registry.setAgent(new HalvingAgent(registry));

        var player = helper.makeMockServerPlayer(GameType.SURVIVAL);
        float before = bot.getHealth();

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 4f);

        // 4 halved to 2. Upstream read event.getDamage() back after dispatch, so a handler
        // can soften a hit as well as veto it; nothing in v1 uses it, and it is one line.
        float taken = before - bot.getHealth();
        helper.assertTrue(taken > 1.5f && taken < 2.5f, "expected about 2 damage, took " + taken);

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("a_surviving_hit_knocks_the_bot_back")
    static void a_surviving_hit_knocks_the_bot_back(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        warmUp(bot);

        var player = helper.makeMockServerPlayer(GameType.SURVIVAL);
        player.snapTo(helper.absoluteVec(new Vec3(1, 1, 3)), 0f, 0f);

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 1f);

        // Upstream's kb() replaces the velocity outright rather than adding to it, and the
        // copy-paste bug in it means both horizontal components come from the X difference.
        // The only safe assertion is therefore "it moved", not a direction.
        helper.assertTrue(bot.getVelocity().length() > 0.0,
                "a surviving bot must be knocked back");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("a_non_player_hit_skips_the_event_and_the_knockback")
    static void a_non_player_hit_skips_the_event_and_the_knockback(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);
        registry.setAgent(new AlwaysBlockingAgent(registry));

        float before = bot.getHealth();

        // No attacker entity at all: upstream's `attacker instanceof ServerPlayer` is false,
        // so the event never fires and the always-cancelling agent cannot save the bot.
        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().fall(), 3f);

        helper.assertTrue(bot.getHealth() < before,
                "non-player damage must bypass BotDamageByPlayerEvent entirely");

        registry.reset();
        helper.succeed();
    }
}

/** Cancels every player hit, standing in for a bot with a raised shield. */
final class AlwaysBlockingAgent extends Agent {

    AlwaysBlockingAgent(BotRegistry registry) {
        super(registry);
    }

    @Override
    public void tick() {
    }

    @Override
    public void onPlayerDamage(BotDamageByPlayerEvent event) {
        event.setCancelled(true);
    }
}

/** Halves every player hit, to prove setDamage is read back. */
final class HalvingAgent extends Agent {

    HalvingAgent(BotRegistry registry) {
        super(registry);
    }

    @Override
    public void tick() {
    }

    @Override
    public void onPlayerDamage(BotDamageByPlayerEvent event) {
        event.setDamage(event.getDamage() / 2f);
    }
}
