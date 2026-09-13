package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.BlockRules;
import net.nuggetmc.tplus.agent.legacy.LegacyUtils;
import net.nuggetmc.tplus.agent.legacy.LevelRules;

/**
 * Tests for the block predicates the agent decides with.
 *
 * <p>In-world from the start: tag membership is unavailable without a loaded datapack, so these
 * cannot be pure tests even though most of them look like they could be (spec §6).
 */
@ForEachTest(groups = BlockRuleTests.GROUP)
public final class BlockRuleTests {

    public static final String GROUP = "bot.blockrules";

    private BlockRuleTests() {
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("air_includes_the_things_a_bot_can_walk_through")
    static void air_includes_the_things_a_bot_can_walk_through(ExtendedGameTestHelper helper) {
        // Upstream's AIR set is not "is this air" — it is "can a bot's line of sight and its
        // body pass through this". Water, lava, fire, snow, vines and tall grass are all in it.
        helper.assertTrue(BlockRules.isAir(Blocks.AIR.defaultBlockState()), "air");
        helper.assertTrue(BlockRules.isAir(Blocks.WATER.defaultBlockState()), "water");
        helper.assertTrue(BlockRules.isAir(Blocks.LAVA.defaultBlockState()), "lava");
        helper.assertTrue(BlockRules.isAir(Blocks.FIRE.defaultBlockState()), "fire");
        helper.assertTrue(BlockRules.isAir(Blocks.SHORT_GRASS.defaultBlockState()), "short grass");
        helper.assertTrue(BlockRules.isAir(Blocks.KELP.defaultBlockState()), "kelp");

        helper.assertFalse(BlockRules.isAir(Blocks.STONE.defaultBlockState()), "stone");
        helper.assertFalse(BlockRules.isAir(Blocks.OAK_FENCE.defaultBlockState()), "a fence");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("water_is_water_and_the_plants_that_live_in_it")
    static void water_is_water_and_the_plants_that_live_in_it(ExtendedGameTestHelper helper) {
        // Upstream's WATER set includes seagrass and kelp, because a bot standing in them is
        // standing in water as far as its swim logic is concerned.
        helper.assertTrue(BlockRules.isWater(Blocks.WATER.defaultBlockState()), "water");
        helper.assertTrue(BlockRules.isWater(Blocks.KELP_PLANT.defaultBlockState()), "kelp plant");
        helper.assertTrue(BlockRules.isWater(Blocks.SEAGRASS.defaultBlockState()), "seagrass");

        helper.assertFalse(BlockRules.isWater(Blocks.LAVA.defaultBlockState()), "lava is not water");
        helper.assertFalse(BlockRules.isWater(Blocks.ICE.defaultBlockState()), "ice is not water");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("free_space_is_blocked_by_a_solid_block")
    static void free_space_is_blocked_by_a_solid_block(ExtendedGameTestHelper helper) {
        Vec3 a = helper.absoluteVec(new Vec3(1.5, 2.5, 1.5));
        Vec3 b = helper.absoluteVec(new Vec3(5.5, 2.5, 1.5));

        helper.assertTrue(LegacyUtils.checkFreeSpace(helper.getLevel(), a, b),
                "an empty corridor must be free");

        helper.setBlock(new BlockPos(3, 2, 1), Blocks.STONE);

        helper.assertFalse(LegacyUtils.checkFreeSpace(helper.getLevel(), a, b),
                "a stone block in the way must block line of sight");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("free_space_is_not_blocked_by_water")
    static void free_space_is_not_blocked_by_water(ExtendedGameTestHelper helper) {
        Vec3 a = helper.absoluteVec(new Vec3(1.5, 2.5, 1.5));
        Vec3 b = helper.absoluteVec(new Vec3(5.5, 2.5, 1.5));

        helper.setBlock(new BlockPos(3, 2, 1), Blocks.WATER);

        // Follows from AIR containing water: a bot will attack a target through water.
        helper.assertTrue(LegacyUtils.checkFreeSpace(helper.getLevel(), a, b),
                "water must not block line of sight");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("free_space_between_identical_points_is_free")
    static void free_space_between_identical_points_is_free(ExtendedGameTestHelper helper) {
        Vec3 a = helper.absoluteVec(new Vec3(1.5, 2.5, 1.5));

        // The ray march divides by the vector's length. Upstream would produce NaN here and
        // the loop bound would be NaN, so the body never ran and it returned true. Ours must
        // reach the same answer without relying on NaN comparison semantics.
        helper.assertTrue(LegacyUtils.checkFreeSpace(helper.getLevel(), a, a),
                "a zero-length ray must be free, not a crash");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x30x5", floor = true)
    @TestHolder("above_ground_means_25_blocks_of_clear_air")
    static void above_ground_means_25_blocks_of_clear_air(ExtendedGameTestHelper helper) {
        BlockPos open = helper.absolutePos(new BlockPos(1, 1, 1));

        helper.assertTrue(LevelRules.aboveGround(helper.getLevel(), Vec3.atBottomCenterOf(open)),
                "an open column must count as above ground");

        helper.setBlock(new BlockPos(1, 12, 1), Blocks.STONE);

        helper.assertFalse(LevelRules.aboveGround(helper.getLevel(), Vec3.atBottomCenterOf(open)),
                "a block anywhere in the 25 above must count as underground");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x30x5", floor = true)
    @TestHolder("above_ground_uses_strict_air_not_the_air_set")
    static void above_ground_uses_strict_air_not_the_air_set(ExtendedGameTestHelper helper) {
        BlockPos open = helper.absolutePos(new BlockPos(1, 1, 1));

        // Upstream compared against Material.AIR here and against its AIR *set* elsewhere, so
        // a bot underwater is not "above ground" even though water is in the AIR set. Easy to
        // unify by accident, and it would change when checkUp gives up on a distant target.
        helper.setBlock(new BlockPos(1, 8, 1), Blocks.WATER);

        helper.assertFalse(LevelRules.aboveGround(helper.getLevel(), Vec3.atBottomCenterOf(open)),
                "water overhead must count as not above ground");
        helper.assertTrue(BlockRules.isAir(Blocks.WATER.defaultBlockState()),
                "even though water is in the AIR set");

        helper.succeed();
    }
}
