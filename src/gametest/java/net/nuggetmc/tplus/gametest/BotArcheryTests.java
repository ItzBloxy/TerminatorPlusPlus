package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.nuggetmc.tplus.agent.legacy.Archery;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.LegacyUtils;
import net.nuggetmc.tplus.agent.legacy.RangedSight;
import net.nuggetmc.tplus.bot.ranged.RangedRule;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;

/**
 * In-world tests for the bow: the slot, the sight predicate, the draw state machine and the
 * {@code tickBot} wiring.
 *
 * <p>Bots inherit the level's default gamemode and the GameTest level is CREATIVE, which makes
 * every damage assertion vacuously true -- so every bot here is set to SURVIVAL explicitly, the
 * way {@code BotCombatTests} does.
 */
@ForEachTest(groups = BotArcheryTests.GROUP)
public final class BotArcheryTests {

    public static final String GROUP = "bot.archery";

    private BotArcheryTests() {
    }

    static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, BlockPos relative,
                     String name) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relative));

        Bot bot = BotFactory.spawn(registry, level, pos, 0f, 0f,
                BotGameProfiles.create(name, null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder("an_empty_bow_slot_means_no_bow")
    static void an_empty_bow_slot_means_no_bow(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1), "SlotBot");

        helper.assertFalse(bot.hasBow(), "a fresh bot must not have a bow");
        helper.assertTrue(bot.getBow().isEmpty(), "the slot must start empty");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder("the_bow_slot_is_independent_of_the_default_item")
    static void the_bow_slot_is_independent_of_the_default_item(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 3), "SlotBot2");

        bot.setDefaultItem(new ItemStack(Items.NETHERITE_SWORD));
        bot.setBow(new ItemStack(Items.BOW));

        helper.assertTrue(bot.hasBow(), "the slot must make hasBow true");
        helper.assertTrue(bot.bowStack().getItem() == Items.BOW, "bowStack must be the slot's bow");

        // The sword is untouched -- this is the whole point of a separate slot.
        bot.setItem(null);
        helper.assertTrue(bot.getMainHandItem().getItem() == Items.NETHERITE_SWORD,
                "setItem(null) must still restore the sword, not the bow");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder("a_bow_as_the_default_item_implies_the_slot")
    static void a_bow_as_the_default_item_implies_the_slot(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "SlotBot3");

        // `/tplus create Archer 3 none none none minecraft:bow` is what an operator types, and
        // it must arm them rather than being quietly ignored.
        bot.setDefaultItem(new ItemStack(Items.BOW));

        helper.assertTrue(bot.hasBow(), "a bow default item must imply the slot");
        helper.assertTrue(bot.bowStack().getItem() == Items.BOW, "bowStack falls back to the default item");
        helper.assertTrue(bot.getBow().isEmpty(), "but the slot itself stays empty");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "9x5x9", floor = true)
    @TestHolder("clearing_the_bow_slot_disarms")
    static void clearing_the_bow_slot_disarms(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 7), "SlotBot4");

        bot.setBow(new ItemStack(Items.BOW));
        helper.assertTrue(bot.hasBow(), "armed");

        bot.setBow(ItemStack.EMPTY);
        helper.assertFalse(bot.hasBow(), "/tplus bow none must disarm");

        bot.setBow(new ItemStack(Items.BOW));
        bot.setBow(null);
        helper.assertFalse(bot.hasBow(), "a null stack must disarm too");

        helper.succeed();
    }

    // ---- RangedSight ------------------------------------------------------

    private static Vec3 at(ExtendedGameTestHelper helper, int x, int y, int z) {
        return Vec3.atCenterOf(helper.absolutePos(new BlockPos(x, y, z)));
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("open_air_is_a_clear_shot")
    static void open_air_is_a_clear_shot(ExtendedGameTestHelper helper) {
        helper.assertTrue(RangedSight.clear(helper.getLevel(), at(helper, 1, 2, 2), at(helper, 13, 2, 2)),
                "12 blocks of air must be a clear shot");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("a_wall_blocks_the_shot")
    static void a_wall_blocks_the_shot(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 2, 2), Blocks.STONE);

        helper.assertFalse(RangedSight.clear(helper.getLevel(), at(helper, 1, 2, 2), at(helper, 13, 2, 2)),
                "a stone block on the line must block the shot");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("water_blocks_a_shot_the_melee_check_would_allow")
    static void water_blocks_a_shot_the_melee_check_would_allow(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 2, 2), Blocks.WATER);

        Vec3 from = at(helper, 1, 2, 2);
        Vec3 to = at(helper, 13, 2, 2);

        // BlockRules.AIR contains WATER, because it is a movement predicate and a bot can swim.
        // An arrow cannot: crossing water drops it to WATER_INERTIA = 0.6 and it falls short.
        // This test is what stops someone "tidying" RangedSight back into checkFreeSpace.
        helper.assertTrue(LegacyUtils.checkFreeSpace(helper.getLevel(), from, to),
                "precondition: the melee check must call this line clear");
        helper.assertFalse(RangedSight.clear(helper.getLevel(), from, to),
                "the ranged check must treat water as blocking");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("lava_blocks_the_shot")
    static void lava_blocks_the_shot(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 2, 2), Blocks.LAVA);

        helper.assertFalse(RangedSight.clear(helper.getLevel(), at(helper, 1, 2, 2), at(helper, 13, 2, 2)),
                "an arrow through lava catches fire; treat it as blocking");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("grass_does_not_block_the_shot")
    static void grass_does_not_block_the_shot(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(7, 2, 2), Blocks.SHORT_GRASS);

        // The vegetation exemptions are kept from BlockRules.AIR. Only water and lava change.
        helper.assertTrue(RangedSight.clear(helper.getLevel(), at(helper, 1, 2, 2), at(helper, 13, 2, 2)),
                "an arrow passes through grass");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "15x5x5", floor = true)
    @TestHolder("a_quarter_block_step_still_catches_a_full_block")
    static void a_quarter_block_step_still_catches_a_full_block(ExtendedGameTestHelper helper) {
        // RangedSight samples 4 points per block instead of checkFreeSpace's 32, which is what
        // makes a 24-block ray affordable. A 0.25 step cannot pass through a 1.0-wide block, and
        // this pins that at the far end of the range where the step count is largest.
        helper.setBlock(new BlockPos(12, 2, 2), Blocks.OBSIDIAN);

        helper.assertFalse(RangedSight.clear(helper.getLevel(), at(helper, 1, 2, 2), at(helper, 13, 2, 2)),
                "a coarser sample must still catch a full block");

        helper.succeed();
    }

    // ---- Archery ----------------------------------------------------------

    /**
     * Ticks every bot's physics, with no agent.
     *
     * <p><b>This is how a bot becomes grounded, and there is no shortcut.</b>
     * {@code Bot.isBotOnGround()} reads {@code groundTicks}, a counter {@code Bot.tickInternal}
     * raises only while {@code checkGround()} is true — it does <i>not</i> read
     * {@code Entity.onGround}, so {@code setOnGround(true)} does nothing for it. A freshly
     * spawned bot has {@code groundTicks == 0} and fails the ranged {@code AIRBORNE} gate until
     * this has run. {@code AgentTests} carries the same helper for the same reason.
     */
    private static void settle(BotRegistry registry, int ticks) {
        for (int i = 0; i < ticks; i++) {
            for (Bot bot : registry.bots()) {
                bot.tick();
            }
        }
    }

    /**
     * A bot high in the air that never lands, so {@code target_flying} fires.
     *
     * <p>Held aloft by re-snapping it every tick rather than by giving it upward velocity: a bot
     * with real velocity drifts, and the test would be measuring the drift rather than the rule.
     * It is deliberately never ticked, so nothing pulls it down between snaps.
     *
     * <p>{@code setOnGround(false)} is correct <i>here</i> and wrong for the shooter: the aloft
     * counter reads {@code target.onGround()}, which is the {@code Entity} field this sets,
     * whereas the shooter's gate reads {@code Bot.groundTicks}, which only ticking raises.
     */
    private static Bot aloftTarget(ExtendedGameTestHelper helper, BotRegistry registry,
                                   BlockPos relative, String name) {
        Bot bot = spawn(helper, registry, relative, name);
        bot.setOnGround(false);
        return bot;
    }

    private static void holdAloft(Bot bot, Vec3 at) {
        bot.snapTo(at.x, at.y, at.z, bot.getYRot(), bot.getXRot());
        bot.setOnGround(false);
    }

    /**
     * Arrows this shooter actually fired.
     *
     * <p>The owner filter is not decoration. GameTests share a level, so a plain box query picks
     * up arrows belonging to tests running at other structure positions — the same trap the
     * conventions warn about for target scans.
     */
    private static long arrowsFrom(ExtendedGameTestHelper helper, Bot shooter) {
        return helper.getLevel()
                .getEntitiesOfClass(Arrow.class, shooter.getBoundingBox().inflate(30))
                .stream()
                .filter(arrow -> arrow.getOwner() == shooter)
                .count();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_bot_draws_and_fires_at_a_flying_target")
    static void a_bot_draws_and_fires_at_a_flying_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer1");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer1");

            // Grounds the shooter: isBotOnGround reads groundTicks, which only ticking raises.
            // Without this every decision below is AIRBORNE.
            settle(registry, 10);

            Archery archery = agent.archery();

            // 40 ticks of aloft to satisfy the rule, then a full 20-tick draw, then the release.
            long arrows = 0;

            for (int i = 0; i < 90 && arrows == 0; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                archery.tick(shooter, target);
                arrows = arrowsFrom(helper, shooter);
            }

            helper.assertTrue(arrows > 0,
                    "a bot must fire at a flying target; last decision was "
                            + archery.lastDecision(shooter));

            helper.succeed();
        } finally {
            // GameTests share a JVM and this agent holds per-bot state; leaving entries behind
            // changes what later tests decide. Same trap BlockRules' static override set has.
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_bot_inside_melee_range_never_draws")
    static void a_bot_inside_melee_range_never_draws(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(6, 1, 7), "Archer2");
            shooter.setBow(new ItemStack(Items.BOW));

            // Two blocks away and aloft: the rule fires, the gate refuses.
            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(8, 2, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(8, 2, 7), "Flyer2");

            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 80; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                helper.assertFalse(archery.tick(shooter, target),
                        "a bot inside 4 blocks must leave the tick unhandled so melee runs");
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.TOO_CLOSE,
                    "expected TOO_CLOSE, got " + archery.lastDecision(shooter));
            helper.assertFalse(archery.isDrawing(shooter), "and it must not be mid-draw");

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_wall_stops_a_bot_drawing")
    static void a_wall_stops_a_bot_drawing(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer3");
            shooter.setBow(new ItemStack(Items.BOW));

            for (int y = 1; y <= 10; y++) {
                helper.setBlock(new BlockPos(7, y, 7), Blocks.OBSIDIAN);
            }

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer3");

            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 80; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                archery.tick(shooter, target);
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.NO_LINE_OF_SIGHT,
                    "expected NO_LINE_OF_SIGHT, got " + archery.lastDecision(shooter));
            helper.assertFalse(archery.isDrawing(shooter), "and it must not be mid-draw");

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_crowded_bot_falls_through_to_navigation")
    static void a_crowded_bot_falls_through_to_navigation(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer4");
            shooter.setBow(new ItemStack(Items.BOW));

            // Four squadmates on top of it. CROWD_LIMIT defaults to 4 within 4 blocks.
            for (int i = 0; i < 4; i++) {
                spawn(helper, registry, new BlockPos(2, 1, 7), "Crowd" + i);
            }

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer4");

            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 80; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                helper.assertFalse(archery.tick(shooter, target),
                        "a crowded bot must leave the tick unhandled so it can push forward");
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.CROWDED,
                    "expected CROWDED, got " + archery.lastDecision(shooter));

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("towering_squadmates_make_the_next_bot_shoot")
    static void towering_squadmates_make_the_next_bot_shoot(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer5");
            shooter.setBow(new ItemStack(Items.BOW));

            // A grounded target, so target_flying cannot be what fires.
            Bot target = spawn(helper, registry, new BlockPos(12, 1, 7), "Ground5");

            // Three squadmates in towerList, near the target. Default quota is 3.
            for (int i = 0; i < 3; i++) {
                Bot towerer = spawn(helper, registry, new BlockPos(11, 1, 7), "Tower" + i);
                registry.state().towerList.put(towerer, towerer.position());
            }

            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 10; i++) {
                shooter.tick();
                target.tick();
                archery.tick(shooter, target);
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.TOWER_QUOTA,
                    "expected TOWER_QUOTA, got " + archery.lastDecision(shooter));

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("entering_ranged_stops_a_mining_animation")
    static void entering_ranged_stops_a_mining_animation(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer6");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer6");

            settle(registry, 10);

            // Stand in for a running swing animation. BOT_STUCK fires precisely when a bot is
            // mining and getting nowhere, so a mining bot entering RANGED is the common path.
            registry.state().miningAnim.put(shooter, 12345);

            Archery archery = agent.archery();

            for (int i = 0; i < 60; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                archery.tick(shooter, target);
            }

            helper.assertFalse(registry.state().miningAnim.containsKey(shooter),
                    "entering RANGED must stop the mining animation, as tower() and resetHand do");

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_bot_on_boat_cooldown_keeps_the_boat")
    static void a_bot_on_boat_cooldown_keeps_the_boat(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer7");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer7");

            settle(registry, 10);

            // boatOverLava runs in miscellaneousChecks, ABOVE the ranged branch, and leaves a
            // boat in the hand. resetHand has the same early return; without it a bot swaps the
            // boat for a bow halfway across a lava lake.
            registry.state().boatCooldown.add(shooter);
            shooter.setItem(new ItemStack(Items.OAK_BOAT));

            Archery archery = agent.archery();

            for (int i = 0; i < 60; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                helper.assertFalse(archery.tick(shooter, target),
                        "a bot on boat cooldown must leave the tick unhandled");
            }

            helper.assertTrue(shooter.getMainHandItem().getItem() == Items.OAK_BOAT,
                    "the boat must still be in hand, got " + shooter.getMainHandItem());

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("resetting_a_drawing_bot_restores_its_hand")
    static void resetting_a_drawing_bot_restores_its_hand(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer8");
            shooter.setDefaultItem(new ItemStack(Items.NETHERITE_SWORD));
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot target = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer8");

            settle(registry, 10);

            Archery archery = agent.archery();

            for (int i = 0; i < 45; i++) {
                holdAloft(target, aloft);
                shooter.tick();
                archery.tick(shooter, target);
            }

            helper.assertTrue(archery.isDrawing(shooter), "precondition: the bot must be drawing");
            helper.assertTrue(shooter.getMainHandItem().getItem() == Items.BOW,
                    "precondition: the bow must be in hand");

            archery.reset(shooter);

            helper.assertFalse(archery.isDrawing(shooter), "reset must drop the draw");
            helper.assertTrue(shooter.getMainHandItem().getItem() == Items.NETHERITE_SWORD,
                    "reset must restore the default item, got " + shooter.getMainHandItem());

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }

    @GameTest(timeoutTicks = 300)
    @EmptyTemplate(value = "15x12x15", floor = true)
    @TestHolder("a_new_target_does_not_inherit_the_old_aloft_count")
    static void a_new_target_does_not_inherit_the_old_aloft_count(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);

        try {
            Bot shooter = spawn(helper, registry, new BlockPos(2, 1, 7), "Archer9");
            shooter.setBow(new ItemStack(Items.BOW));

            Vec3 aloft = Vec3.atCenterOf(helper.absolutePos(new BlockPos(12, 8, 7)));
            Bot flyer = aloftTarget(helper, registry, new BlockPos(12, 8, 7), "Flyer9");
            Bot walker = spawn(helper, registry, new BlockPos(12, 1, 7), "Walker9");

            settle(registry, 10);

            Archery archery = agent.archery();

            // Build up well past the 40-tick threshold against the flyer.
            for (int i = 0; i < 60; i++) {
                holdAloft(flyer, aloft);
                shooter.tick();
                archery.tick(shooter, flyer);
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() == RangedRule.TARGET_FLYING,
                    "precondition: expected TARGET_FLYING, got " + archery.lastDecision(shooter));

            // Targeting.locateTarget runs every tick and may hand back a different entity. If the
            // count carried across the switch, this grounded bot would inherit 60 ticks of
            // "aloft" and be shot at while standing on the floor.
            for (int i = 0; i < 5; i++) {
                walker.tick();
                shooter.tick();
                archery.tick(shooter, walker);
            }

            helper.assertTrue(archery.lastDecision(shooter).reason() != RangedRule.TARGET_FLYING,
                    "a grounded target must not inherit the flyer's aloft count; got "
                            + archery.lastDecision(shooter));

            helper.succeed();
        } finally {
            agent.stopAllTasks();
            agent.setEnabled(false);
        }
    }
}
