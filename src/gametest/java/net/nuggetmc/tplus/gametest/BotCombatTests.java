package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
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
 *
 * <p><b>A mock player can deal damage but cannot receive it, and cannot live in the level.</b>
 * {@code makeMockServerPlayer} builds a ServerPlayer with a null {@code connection}. Passing it to
 * {@code damageSources().playerAttack(...)} is fine — nothing sends it anything. But
 * {@code hurtServer} on one throws, because ServerPlayer's damage path sends packets; and
 * {@code addFreshEntity} on one crashes the whole server, because the world tick does too.
 * Neither surfaces as a readable failure: the first is swallowed into an
 * {@code UnknownGameTestException}, the second kills the run.
 *
 * <p>So: mock players are attackers here, and <b>bots are targets</b> — a Bot carries
 * {@code BotConnection}, which swallows every send. A test that genuinely needs a level-resident
 * player (target selection, phase 4) needs {@code makeMockServerPlayerInLevel}, which goes through
 * {@code placeNewPlayer} with an EmbeddedChannel behind it.
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

    /** A second bot, usable as a damage target because BotConnection swallows packets. */
    private static Bot spawnTarget(ExtendedGameTestHelper helper, BotRegistry registry,
                                   BlockPos relative, String name) {
        Bot bot = BotFactory.spawn(registry, helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(relative)), 0f, 0f,
                BotGameProfiles.create(name, null), false);
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

    // ---- attack -------------------------------------------------------------

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("attack_hurts_the_target_for_the_held_items_legacy_damage")
    static void attack_hurts_the_target_for_the_held_items_legacy_damage(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        warmUp(bot);

        Bot target = spawnTarget(helper, registry, new BlockPos(4, 1, 3), "TargetBot");
        warmUp(target);
        target.invulnerableTime = 0;

        // A netherite sword is 8 in the 1.8 table regardless of what the item really does now.
        bot.setDefaultItem(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.NETHERITE_SWORD));

        float before = target.getHealth();
        bot.attack(target);
        float taken = before - target.getHealth();

        helper.assertTrue(taken > 7.5f && taken < 8.5f,
                "expected about 8 damage from the legacy table, took " + taken);

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("attack_with_an_empty_hand_is_the_fist_damage")
    static void attack_with_an_empty_hand_is_the_fist_damage(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        warmUp(bot);

        Bot target = spawnTarget(helper, registry, new BlockPos(4, 1, 3), "TargetBot");
        warmUp(target);
        target.invulnerableTime = 0;

        // defaultItem starts EMPTY, whose item is AIR — unlisted, so 0.25.
        float before = target.getHealth();
        bot.attack(target);

        helper.assertTrue(before - target.getHealth() < 1.0f,
                "a bare-handed bot must deal the 0.25 fist damage");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("attack_turns_the_bot_toward_its_target")
    static void attack_turns_the_bot_toward_its_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        warmUp(bot);
        bot.setYRot(0f);

        Bot target = spawnTarget(helper, registry, new BlockPos(5, 1, 3), "TargetBot");
        warmUp(target);
        target.invulnerableTime = 0;

        bot.attack(target);

        // The target is due east, which is yaw -90 — or equivalently 270, which is what
        // BotMath.fetchYawPitch produces: it normalises into [0, 360), where vanilla's own
        // getYRot is usually (-180, 180]. Nothing downstream cares (setRot takes yaw % 360 and
        // the wire format is a byte), but an assertion that subtracts has to wrap.
        float off = Math.abs(Mth.wrapDegrees(bot.getYRot() - (-90f)));

        helper.assertTrue(off < 5f,
                "attack must face the target first, yaw is " + bot.getYRot());

        registry.reset();
        helper.succeed();
    }

    // ---- the event bridges --------------------------------------------------

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("bot_death_reaches_the_agent_through_livingdrops")
    static void bot_death_reaches_the_agent_through_livingdrops(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        RecordingAgent agent = new RecordingAgent(registry);
        registry.setAgent(agent);

        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);

        // Something to drop, so the drop path is actually entered.
        bot.setItem(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.DIAMOND));

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().fall(), 1000f);

        // NeoForge's LivingDropsEvent is the only staging point where clearing the drops still
        // suppresses them, and Player.die reaches it through dropAllDeathLoot. If this ever
        // stops firing, the /tplus drops toggle silently does nothing.
        helper.assertTrue(agent.deaths > 0, "BotDeathEvent must reach the agent");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("a_bot_killing_a_bot_credits_the_kill")
    static void a_bot_killing_a_bot_credits_the_kill(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();

        Bot killer = spawn(helper, registry, new BlockPos(2, 1, 3));
        Bot victim = BotFactory.spawn(registry, helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 1, 3))), 0f, 0f,
                BotGameProfiles.create("VictimBot", null), false);
        victim.setGameMode(GameType.SURVIVAL);

        warmUp(killer);
        warmUp(victim);
        victim.invulnerableTime = 0;

        helper.assertValueEqual(killer.getKills(), 0, "no kills yet");

        // Bot extends ServerPlayer, so the victim's hurtServer sees a "player" attacker and
        // fires BotKilledByPlayerEvent; Agent.onBotKilledByPlayer looks the killer up by entity
        // id and finds the bot. This is the whole reason the damage path does not exclude bots.
        victim.hurtServer(helper.getLevel(),
                helper.getLevel().damageSources().playerAttack(killer), 1000f);

        helper.assertValueEqual(killer.getKills(), 1, "the killing bot must be credited");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("the_shield_is_inert_because_bots_never_tick_item_use")
    static void the_shield_is_inert_because_bots_never_tick_item_use(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);

        bot.setShield(true);
        bot.block(40, 40);
        warmUp(bot);

        // Documented dead feature, faithful to upstream. isBlocking() needs blockDelayTicks to
        // have elapsed since the item went into use, counted from useItemRemaining — which only
        // updateUsingItem decrements, which only LivingEntity.tick() reaches, which never runs
        // for a bot. Upstream's doTick was identical, so its shields never worked either.
        //
        // This asserts the broken state on purpose. If it ever starts failing, someone has
        // changed what a bot ticks, and that has much wider consequences than shields.
        helper.assertFalse(bot.isBotBlocking(),
                "a bot cannot block: LivingEntity.tick never runs for it, so the shield's "
                        + "warmup never elapses");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("removing_a_bot_forgets_its_agent_state")
    static void removing_a_bot_forgets_its_agent_state(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        registry.state().noJump.add(bot);
        registry.state().btCheck.put(bot, true);

        registry.remove(bot);

        // Upstream never cleared these, so every dead bot leaked an entry in nine collections.
        helper.assertFalse(registry.state().noJump.contains(bot), "noJump must be cleared");
        helper.assertFalse(registry.state().btCheck.containsKey(bot), "btCheck must be cleared");

        bot.removeBot();
        helper.succeed();
    }
}

/** Counts the lifecycle callbacks it receives. */
final class RecordingAgent extends Agent {

    int deaths;

    RecordingAgent(BotRegistry registry) {
        super(registry);
    }

    @Override
    public void tick() {
    }

    @Override
    public void onBotDeath(net.nuggetmc.tplus.event.BotDeathEvent event) {
        deaths++;
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
