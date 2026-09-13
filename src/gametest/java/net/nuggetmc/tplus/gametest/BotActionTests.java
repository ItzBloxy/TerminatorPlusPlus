package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.BlockRules;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.Mining;
import net.nuggetmc.tplus.agent.legacy.ScanOffset;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.bot.EquipmentTier;
import net.nuggetmc.tplus.motion.MotionVec;

/**
 * In-world tests for the bot action API.
 *
 * <p>These cannot be unit tests: every one of them either sends a packet, reads a
 * {@code BlockState}, or asks the level for an entity, and Plan A established that
 * {@code EphemeralTestServerProvider} has no levels at all.
 *
 * <p>Positions are relative to the test structure; {@code helper.absolutePos} converts.
 */
@ForEachTest(groups = BotActionTests.GROUP)
public final class BotActionTests {

    public static final String GROUP = "bot.actions";

    private BotActionTests() {
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relative));

        return BotFactory.spawn(registry, level, pos, 0f, 0f,
                BotGameProfiles.create("ActionBot", null), false);
    }

    /** A bot with a tier, and a stone block in its head space, which is where ScanOffset.AT points. */
    private static Bot miner(ExtendedGameTestHelper helper, BotRegistry registry,
                             int x, int z, EquipmentTier tier) {
        Bot bot = spawn(helper, registry, new BlockPos(x, 1, z));
        bot.setToolTier(tier);
        helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
        return bot;
    }

    private static boolean isAir(ExtendedGameTestHelper helper, int x, int y, int z) {
        return helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, y, z))).isAir();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("a_bot_mines_with_the_tier_it_was_given")
    static void a_bot_mines_with_the_tier_it_was_given(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));

        Bot bot = miner(helper, registry, 3, 3, EquipmentTier.NETHERITE);

        // A direct preBreak call, not 200 ticks of hunting. move() adds Math.random() to every
        // jump, so a ticked test measures the walk rather than the tool choice.
        new Mining(registry.state(), registry.agent())
                .preBreak(bot, helper.absolutePos(new BlockPos(3, 2, 3)), ScanOffset.AT);

        helper.assertTrue(bot.getMainHandItem().is(Items.NETHERITE_PICKAXE),
                "a netherite bot must mine stone with its own pickaxe, not an iron one");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("the_default_tool_tier_is_upstreams_iron")
    static void the_default_tool_tier_is_upstreams_iron(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));

        // Nothing calls setToolTier. This is the test that keeps making the tier configurable a
        // change in capability rather than a change in behaviour.
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);

        new Mining(registry.state(), registry.agent())
                .preBreak(bot, helper.absolutePos(new BlockPos(3, 2, 3)), ScanOffset.AT);

        helper.assertTrue(bot.getMainHandItem().is(Items.IRON_PICKAXE),
                "an unconfigured bot must still mine with iron");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("the_none_tier_floors_at_wood")
    static void the_none_tier_floors_at_wood(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));

        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        bot.setToolTier(EquipmentTier.NONE);

        // `none` has to parse in the tools slot because it is the create chain's filler word,
        // but a bare-handed bot scores 1.0 against everything and would take 120 ticks a block --
        // six times upstream's twenty, as the result of skipping an argument.
        helper.assertTrue(bot.getToolTier() == EquipmentTier.WOOD,
                "the none tier must floor at wood for tools");

        helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
        new Mining(registry.state(), registry.agent())
                .preBreak(bot, helper.absolutePos(new BlockPos(3, 2, 3)), ScanOffset.AT);

        helper.assertTrue(bot.getMainHandItem().is(Items.WOODEN_PICKAXE),
                "and it must mine with a wooden pickaxe, not an empty hand");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("iron_still_breaks_a_block_in_twenty_ticks")
    static void iron_still_breaks_a_block_in_twenty_ticks(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));

        Bot bot = miner(helper, registry, 3, 3, EquipmentTier.IRON);

        new Mining(registry.state(), registry.agent())
                .preBreak(bot, helper.absolutePos(new BlockPos(3, 2, 3)), ScanOffset.AT);

        // Only the scheduler is ticked, deliberately: ticking the registry would run the agent,
        // the agent moves bots, and the break task cancels itself the moment the bot is no
        // longer aiming at the block. Ticking the scheduler alone makes this exact.
        int ticks = 0;

        for (int tick = 1; tick <= 100 && ticks == 0; tick++) {
            registry.scheduler().tick();

            if (isAir(helper, 3, 2, 3)) {
                ticks = tick;
            }
        }

        // The anchor for the whole speed change. Upstream advanced one fixed stage every two
        // ticks, so every block took twenty ticks whatever the bot held; STAGE_COST is defined
        // as iron's progress in one run precisely so that iron still does. If this number moves,
        // the speed model has drifted off upstream rather than extended it.
        helper.assertValueEqual(ticks, 20, "iron must still break a block in exactly twenty ticks");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x5x7", floor = true)
    @TestHolder("a_better_tool_breaks_a_block_sooner")
    static void a_better_tool_breaks_a_block_sooner(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));
        Mining mining = new Mining(registry.state(), registry.agent());

        // Three bots in three columns. They do not interact: each break task is keyed on its own
        // block position and re-derives its target from its own bot.
        Bot wood = miner(helper, registry, 2, 3, EquipmentTier.WOOD);
        Bot iron = miner(helper, registry, 6, 3, EquipmentTier.IRON);
        Bot netherite = miner(helper, registry, 10, 3, EquipmentTier.NETHERITE);

        mining.preBreak(wood, helper.absolutePos(new BlockPos(2, 2, 3)), ScanOffset.AT);
        mining.preBreak(iron, helper.absolutePos(new BlockPos(6, 2, 3)), ScanOffset.AT);
        mining.preBreak(netherite, helper.absolutePos(new BlockPos(10, 2, 3)), ScanOffset.AT);

        int woodTicks = 0;
        int ironTicks = 0;
        int netheriteTicks = 0;

        // One scheduler drives all three, so they are measured against the same clock rather
        // than in three separate runs. Wood is the slowest at 60 ticks, so it ends the loop.
        for (int tick = 1; tick <= 100 && woodTicks == 0; tick++) {
            registry.scheduler().tick();

            if (netheriteTicks == 0 && isAir(helper, 10, 2, 3)) {
                netheriteTicks = tick;
            }
            if (ironTicks == 0 && isAir(helper, 6, 2, 3)) {
                ironTicks = tick;
            }
            if (woodTicks == 0 && isAir(helper, 2, 2, 3)) {
                woodTicks = tick;
            }
        }

        // Asserted as an ordering rather than three exact numbers: the exact ones are pinned by
        // iron_still_breaks_a_block_in_twenty_ticks, and this is the property that makes the
        // tier worth setting at all.
        helper.assertTrue(netheriteTicks > 0 && netheriteTicks < ironTicks,
                "netherite (" + netheriteTicks + ") must beat iron (" + ironTicks + ")");
        helper.assertTrue(ironTicks > 0 && ironTicks < woodTicks,
                "iron (" + ironTicks + ") must beat wood (" + woodTicks + ")");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("a_tier_lands_in_the_right_four_armour_slots")
    static void a_tier_lands_in_the_right_four_armour_slots(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 1, 2));

        EquipmentTier.DIAMOND.equipArmor(bot);

        // EquipmentTierTest pins armorPiece(i) against ARMOR_SLOTS[i], but only inside the table.
        // This is the pairing itself: transpose the two and a bot wears its boots on its head,
        // which nothing else in the codebase would notice and which no unit test can reach,
        // because equipping builds ItemStacks.
        helper.assertTrue(bot.getItemBySlot(EquipmentSlot.FEET).is(Items.DIAMOND_BOOTS), "feet");
        helper.assertTrue(bot.getItemBySlot(EquipmentSlot.LEGS).is(Items.DIAMOND_LEGGINGS), "legs");
        helper.assertTrue(bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), "chest");
        helper.assertTrue(bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "head");

        // NONE strips all four through the same loop that filled them -- armorPiece returns null
        // past the end of its empty array rather than throwing.
        EquipmentTier.NONE.equipArmor(bot);

        for (EquipmentSlot slot : EquipmentTier.ARMOR_SLOTS) {
            helper.assertTrue(bot.getItemBySlot(slot).isEmpty(), "the none tier must strip " + slot);
        }

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("look_down_pitches_to_90_and_keeps_yaw")
    static void look_down_pitches_to_90_and_keeps_yaw(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setYRot(37f);

        bot.look(Direction.DOWN);

        helper.assertValueEqual(bot.getXRot(), 90f, "pitch after looking down");
        helper.assertValueEqual(bot.getYRot(), 37f,
                "yaw must survive a vertical look — upstream passed keepYaw for UP and DOWN");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("look_up_pitches_to_minus_90")
    static void look_up_pitches_to_minus_90(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.look(Direction.UP);

        helper.assertValueEqual(bot.getXRot(), -90f, "pitch after looking up");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("look_north_sets_yaw_and_leaves_pitch_level")
    static void look_north_sets_yaw_and_leaves_pitch_level(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setXRot(45f);

        bot.look(Direction.NORTH);

        // A horizontal look does NOT keep yaw, so both channels are recomputed. North is
        // yaw 180 in Minecraft's convention, and a flat direction gives pitch 0.
        helper.assertValueEqual(bot.getYRot(), 180f, "yaw after looking north");

        // Primitive ==, not assertValueEqual: fetchPitch returns -0.0f for a flat direction,
        // and assertValueEqual boxes to Float and calls equals, which compares bit patterns —
        // so Float.valueOf(-0.0f).equals(0.0f) is false. -0.0 is the correct answer here.
        helper.assertTrue(bot.getXRot() == 0f,
                "pitch after a horizontal look, was " + bot.getXRot());

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("face_location_turns_the_head_too")
    static void face_location_turns_the_head_too(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        Vec3 east = Vec3.atCenterOf(helper.absolutePos(new BlockPos(6, 1, 1)));
        bot.faceLocation(east);

        // Body yaw and head yaw must agree: the client draws the head from yHeadRot and the
        // body from yRot, and upstream sent ClientboundRotateHeadPacket for exactly this.
        helper.assertValueEqual(bot.getYHeadRot(), bot.getYRot(), "head yaw must follow body yaw");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("face_location_at_own_position_does_not_produce_nan")
    static void face_location_at_own_position_does_not_produce_nan(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        float before = bot.getYRot();

        // A zero direction vector normalises to NaN by design (see the plan's hazard note).
        // setRot would then poison the rotation permanently and the bot would never aim at
        // anything again, so faceLocation has to refuse the degenerate case.
        bot.faceLocation(bot.position());

        helper.assertValueEqual(bot.getYRot(), before, "yaw must be unchanged, not NaN");
        helper.assertFalse(Float.isNaN(bot.getXRot()), "pitch must not be NaN");

        registry.reset();
        helper.succeed();
    }

    // ---- pose, animation and equipment -------------------------------------

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("sneak_then_stand_toggles_the_shift_flag")
    static void sneak_then_stand_toggles_the_shift_flag(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.sneak();
        helper.assertTrue(bot.isShiftKeyDown(), "sneak must set the shift flag");

        bot.stand();
        helper.assertFalse(bot.isShiftKeyDown(), "stand must clear the shift flag");
        helper.assertFalse(bot.isSwimming(), "stand must clear the swim flag too");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("swim_sets_the_swim_flag")
    static void swim_sets_the_swim_flag(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.swim();

        helper.assertTrue(bot.isSwimming(), "swim must set the swim flag");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("set_item_puts_the_stack_in_the_main_hand")
    static void set_item_puts_the_stack_in_the_main_hand(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.setItem(new ItemStack(Items.DIAMOND_PICKAXE));

        // PlayerEquipment routes MAINHAND to inventory.setSelectedItem, so both views agree.
        helper.assertTrue(bot.getMainHandItem().is(Items.DIAMOND_PICKAXE), "main hand item");
        helper.assertTrue(bot.getInventory().getSelectedItem().is(Items.DIAMOND_PICKAXE),
                "the inventory's selected slot is the main hand for a player");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("a_null_item_restores_the_default_item")
    static void a_null_item_restores_the_default_item(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        // Upstream's contract: setItem(null) means "go back to the default", and the agent
        // relies on it — resetHand and move both call setItem(null) every few ticks to drop
        // whatever tool the mining code put there.
        bot.setDefaultItem(new ItemStack(Items.WOODEN_AXE));
        bot.setItem(new ItemStack(Items.DIAMOND_PICKAXE));

        bot.setItem(null);

        helper.assertTrue(bot.getMainHandItem().is(Items.WOODEN_AXE),
                "setItem(null) must restore the default item, not empty the hand");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("set_shield_equips_and_unequips_the_offhand")
    static void set_shield_equips_and_unequips_the_offhand(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.setShield(true);
        helper.assertTrue(bot.getOffhandItem().is(Items.SHIELD), "offhand after enabling the shield");

        bot.setShield(false);
        helper.assertTrue(bot.getOffhandItem().isEmpty(), "offhand after disabling the shield");

        registry.reset();
        helper.succeed();
    }

    // ---- velocity, combat and state ----------------------------------------

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("get_velocity_returns_a_copy_not_the_live_vector")
    static void get_velocity_returns_a_copy_not_the_live_vector(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setVelocity(new MotionVec(0.1, 0.2, 0.3));

        // The agent does exactly this — checkUp's `npc.getVelocity().add(vector)` mutates
        // whatever it is handed. If that is the live vector, the bot's physics is corrupted
        // from the first tick and nothing downstream reports why.
        bot.getVelocity().add(new MotionVec(99, 99, 99));

        helper.assertValueEqual(bot.getBotVelocity().getX(), 0.1, "x must be untouched");
        helper.assertValueEqual(bot.getBotVelocity().getY(), 0.2, "y must be untouched");
        helper.assertValueEqual(bot.getBotVelocity().getZ(), 0.3, "z must be untouched");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("set_velocity_writes_through_to_the_live_vector")
    static void set_velocity_writes_through_to_the_live_vector(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        MotionVec live = bot.getBotVelocity();

        bot.setVelocity(new MotionVec(1, 2, 3));

        // The velocity field is final (BotPhysics holds and mutates it), so setVelocity
        // copies component-wise rather than rebinding. Same observable result, and the
        // reference BotPhysics captured stays valid.
        helper.assertValueEqual(live.getY(), 2.0, "the same object must see the new value");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("walk_caps_the_combined_speed")
    static void walk_caps_the_combined_speed(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setVelocity(new MotionVec(0.3, 0, 0.3));

        bot.walk(new MotionVec(0.3, 0, 0.3));

        // 0.6,0,0.6 has length 0.848; upstream normalised and scaled back to exactly 0.4.
        helper.assertTrue(Math.abs(bot.getVelocity().length() - 0.4) < 1.0E-6,
                "walk must clamp to 0.4, got " + bot.getVelocity().length());

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("walk_below_the_cap_is_a_plain_sum")
    static void walk_below_the_cap_is_a_plain_sum(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setVelocity(new MotionVec(0.1, 0, 0));

        bot.walk(new MotionVec(0.1, 0, 0));

        helper.assertTrue(Math.abs(bot.getVelocity().getX() - 0.2) < 1.0E-9,
                "under the cap, walk just adds; got " + bot.getVelocity().getX());

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("is_falling_is_the_minus_zero_point_eight_threshold")
    static void is_falling_is_the_minus_zero_point_eight_threshold(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.setVelocity(new MotionVec(0, -0.79, 0));
        helper.assertFalse(bot.isFalling(), "-0.79 is not falling");

        bot.setVelocity(new MotionVec(0, -0.81, 0));
        helper.assertTrue(bot.isFalling(), "-0.81 is falling");

        // The same constant gates fall damage in fallDamageCheck and the MLG attempt in
        // BlockScan.tryPreMLG. Three behaviours hang off this number.
        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("tick_delay_is_a_modulus_of_alive_ticks")
    static void tick_delay_is_a_modulus_of_alive_ticks(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        // aliveTicks is 0 on spawn, and 0 % n == 0, so every delay fires on the first tick.
        // The agent's "every 3 ticks" attack and "every 20 ticks" centring both rely on it.
        helper.assertTrue(bot.tickDelay(3), "delay 3 must fire at tick 0");
        helper.assertTrue(bot.tickDelay(20), "delay 20 must fire at tick 0");

        bot.tick();
        helper.assertFalse(bot.tickDelay(3), "delay 3 must not fire at tick 1");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("the_spawn_offset_is_inside_a_three_block_circle")
    static void the_spawn_offset_is_inside_a_three_block_circle(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        // Every bot gets a fixed random offset at construction so a crowd of bots chasing
        // one player spreads out instead of stacking. Upstream: MathUtils.circleOffset(3).
        MotionVec offset = bot.getOffset();

        helper.assertTrue(offset.length() <= 3.0 + 1.0E-9,
                "offset must lie within radius 3, got " + offset.length());
        helper.assertTrue(offset.getY() == 0.0, "the offset is horizontal, got " + offset.getY());

        registry.reset();
        helper.succeed();
    }

    // ---- block placing ------------------------------------------------------

    @GameTest
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("attempt_block_place_fills_an_empty_space")
    static void attempt_block_place_fills_an_empty_space(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        BlockPos target = helper.absolutePos(new BlockPos(3, 1, 1));
        bot.attemptBlockPlace(target, Blocks.COBBLESTONE, false);

        helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(3, 1, 1));

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("attempt_block_place_honours_an_operators_solid_override")
    static void attempt_block_place_honours_an_operators_solid_override(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        BlockPos relative = new BlockPos(3, 1, 1);
        helper.setBlock(relative, Blocks.TORCH);

        try {
            BlockRules.addSolid(Blocks.TORCH);
            bot.attemptBlockPlace(helper.absolutePos(relative), Blocks.COBBLESTONE, false);

            // Upstream guards this with LegacyMats.isSolid, which consults the override. Guarding
            // with the raw vanilla predicate instead -- which this port did until Plan C -- makes
            // a bot overwrite exactly the blocks an operator asked it to respect.
            helper.assertBlockPresent(Blocks.TORCH, relative);
        } finally {
            BlockRules.clearSolidOverrides();
        }

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder("attempt_block_place_refuses_to_overwrite_a_solid_block")
    static void attempt_block_place_refuses_to_overwrite_a_solid_block(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        BlockPos relative = new BlockPos(3, 1, 1);
        helper.setBlock(relative, Blocks.OBSIDIAN);

        bot.attemptBlockPlace(helper.absolutePos(relative), Blocks.COBBLESTONE, false);

        // Upstream guarded on LegacyMats.isSolid. Without the guard a bot towering out of
        // lava would happily replace the bedrock it is standing on.
        helper.assertBlockPresent(Blocks.OBSIDIAN, relative);

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("attempt_block_place_down_looks_down_rather_than_at_the_block")
    static void attempt_block_place_down_looks_down_rather_than_at_the_block(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setYRot(42f);

        bot.attemptBlockPlace(helper.absolutePos(new BlockPos(3, 1, 1)), Blocks.COBBLESTONE, true);

        // down = true means look(DOWN), which keeps yaw. down = false means faceLocation,
        // which does not. The agent picks between them per situation.
        helper.assertValueEqual(bot.getXRot(), 90f, "pitch when placing downward");
        helper.assertValueEqual(bot.getYRot(), 42f, "yaw must survive a downward place");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("attempt_block_place_holds_cobblestone")
    static void attempt_block_place_holds_cobblestone(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        // Upstream always put COBBLESTONE in hand, even when placing something else — the
        // `type` parameter and the held item are independent, and only one call site ever
        // passes a different type. Ported as-is.
        bot.attemptBlockPlace(helper.absolutePos(new BlockPos(3, 1, 1)), Blocks.COBBLESTONE, false);

        helper.assertTrue(bot.getMainHandItem().is(Items.COBBLESTONE), "held item after placing");

        registry.reset();
        helper.succeed();
    }
}
