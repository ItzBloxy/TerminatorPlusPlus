package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;

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
}
