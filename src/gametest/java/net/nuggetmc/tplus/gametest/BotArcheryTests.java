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
import net.nuggetmc.tplus.agent.legacy.LegacyUtils;
import net.nuggetmc.tplus.agent.legacy.RangedSight;
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
}
