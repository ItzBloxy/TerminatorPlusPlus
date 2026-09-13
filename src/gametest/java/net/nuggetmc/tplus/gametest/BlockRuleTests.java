package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.nuggetmc.tplus.agent.legacy.BlockPlacement;
import net.nuggetmc.tplus.agent.legacy.BlockRules;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.agent.legacy.LegacyUtils;
import net.nuggetmc.tplus.agent.legacy.LevelRules;

import java.util.OptionalDouble;

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
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("fences_include_walls_and_stained_panes_but_not_plain_glass")
    static void fences_include_walls_and_stained_panes_but_not_plain_glass(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.isFence(Blocks.OAK_FENCE.defaultBlockState()), "oak fence");
        helper.assertTrue(BlockRules.isFence(Blocks.NETHER_BRICK_FENCE.defaultBlockState()), "nether fence");
        helper.assertTrue(BlockRules.isFence(Blocks.COBBLESTONE_WALL.defaultBlockState()), "wall");

        // Upstream excluded GLASS_PANE and IRON_BARS by name, but every *stained* pane matched
        // Bukkit's Fence data class and stayed in — so bots treat a stained pane as a fence,
        // something to break through at foot level. It looks like a bug and it is, but
        // checkNearby scans for fences at foot height before anything else, so it is
        // load-bearing.
        helper.assertTrue(BlockRules.isFence(Blocks.STAINED_GLASS_PANE.pick(DyeColor.WHITE)
                        .defaultBlockState()),
                "a stained pane was in upstream's FENCE set");
        helper.assertFalse(BlockRules.isFence(Blocks.GLASS_PANE.defaultBlockState()),
                "plain glass pane was excluded by name");
        helper.assertFalse(BlockRules.isFence(Blocks.IRON_BARS.defaultBlockState()),
                "iron bars were excluded by name");

        helper.assertFalse(BlockRules.isFence(Blocks.OAK_FENCE_GATE.defaultBlockState()),
                "gates are a separate set");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("gates_are_gates")
    static void gates_are_gates(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.isGate(Blocks.OAK_FENCE_GATE.defaultBlockState()), "oak gate");
        helper.assertTrue(BlockRules.isGate(Blocks.WARPED_FENCE_GATE.defaultBlockState()), "warped gate");
        helper.assertFalse(BlockRules.isGate(Blocks.OAK_FENCE.defaultBlockState()), "a fence is not a gate");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("breakable_means_the_bot_can_walk_through_it")
    static void breakable_means_the_bot_can_walk_through_it(ExtendedGameTestHelper helper) {
        // BREAK answers "is this space already clear". checkAt uses it: if the block at head
        // height is NOT in BREAK, the bot stops and mines it.
        helper.assertTrue(BlockRules.isBreak(Blocks.AIR.defaultBlockState()), "air");
        helper.assertTrue(BlockRules.isBreak(Blocks.WATER.defaultBlockState()), "water");
        helper.assertTrue(BlockRules.isBreak(Blocks.TALL_GRASS.defaultBlockState()), "tall grass");
        helper.assertTrue(BlockRules.isBreak(Blocks.SUGAR_CANE.defaultBlockState()), "sugar cane");
        helper.assertTrue(BlockRules.isBreak(Blocks.TWISTING_VINES.defaultBlockState()), "twisting vines");

        helper.assertFalse(BlockRules.isBreak(Blocks.STONE.defaultBlockState()), "stone");

        // SHORT_GRASS is in AIR but NOT in upstream's BREAK set. That asymmetry is upstream's
        // and it is easy to "tidy" away; a bot looks through short grass but stops and mines it
        // if it is at head height.
        helper.assertFalse(BlockRules.isBreak(Blocks.SHORT_GRASS.defaultBlockState()),
                "short grass is deliberately absent from BREAK");
        helper.assertTrue(BlockRules.isAir(Blocks.SHORT_GRASS.defaultBlockState()),
                "but it is present in AIR");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("no_crack_is_the_set_with_no_break_animation")
    static void no_crack_is_the_set_with_no_break_animation(ExtendedGameTestHelper helper) {
        // Fluids and air get no crack animation and are never "broken" — blockBreakEffect
        // returns immediately for them, and downMine skips its nudge.
        helper.assertTrue(BlockRules.isNoCrack(Blocks.WATER.defaultBlockState()), "water");
        helper.assertTrue(BlockRules.isNoCrack(Blocks.LAVA.defaultBlockState()), "lava");
        helper.assertTrue(BlockRules.isNoCrack(Blocks.AIR.defaultBlockState()), "air");
        helper.assertFalse(BlockRules.isNoCrack(Blocks.DIRT.defaultBlockState()), "dirt cracks");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("obstacles_are_things_a_bot_must_break_to_pass")
    static void obstacles_are_things_a_bot_must_break_to_pass(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.isObstacle(Blocks.IRON_BARS.defaultBlockState()), "iron bars");
        helper.assertTrue(BlockRules.isObstacle(Blocks.COBWEB.defaultBlockState()), "cobweb");
        helper.assertTrue(BlockRules.isObstacle(Blocks.SWEET_BERRY_BUSH.defaultBlockState()), "berries");
        helper.assertTrue(BlockRules.isObstacle(Blocks.FLOWER_POT.defaultBlockState()), "flower pot");
        helper.assertTrue(BlockRules.isObstacle(Blocks.POTTED_CACTUS.defaultBlockState()), "potted plant");
        helper.assertTrue(BlockRules.isObstacle(Blocks.GLASS_PANE.defaultBlockState()), "glass pane");
        helper.assertTrue(BlockRules.isObstacle(Blocks.END_ROD.defaultBlockState()), "end rod");

        helper.assertFalse(BlockRules.isObstacle(Blocks.STONE.defaultBlockState()), "stone is not an obstacle");

        // Upstream listed Material.CHAIN; the block is Blocks.IRON_CHAIN in 26.2, and the
        // lightning rod is now a whole weathering-copper family rather than one constant.
        // Both are the single clearest argument for tags over hand-written lists — spec §4.2 —
        // and the plan walked into the first one before this was caught.
        helper.assertTrue(BlockRules.isObstacle(Blocks.IRON_CHAIN.defaultBlockState()), "chain");
        // WeatheringCopperCollection is a record of two ByState records — weathering and waxed
        // — each with unaffected/exposed/weathered/oxidized accessors. There is no flat pick().
        // Testing two weather states and a waxed one is the point of using instanceof in
        // isObstacle rather than enumerating eight constants.
        helper.assertTrue(BlockRules.isObstacle(Blocks.LIGHTNING_ROD.weathering().unaffected()
                .defaultBlockState()), "lightning rod");
        helper.assertTrue(BlockRules.isObstacle(Blocks.LIGHTNING_ROD.weathering().oxidized()
                .defaultBlockState()), "and every weathered variant of it");
        helper.assertTrue(BlockRules.isObstacle(Blocks.LIGHTNING_ROD.waxed().unaffected()
                .defaultBlockState()), "and the waxed ones");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("can_stand_on_covers_non_solid_blocks_that_hold_a_bot_up")
    static void can_stand_on_covers_non_solid_blocks_that_hold_a_bot_up(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.canStandOn(Blocks.SNOW.defaultBlockState()), "snow layer");
        helper.assertTrue(BlockRules.canStandOn(Blocks.LADDER.defaultBlockState()), "ladder");
        helper.assertTrue(BlockRules.canStandOn(Blocks.SCAFFOLDING.defaultBlockState()), "scaffolding");
        helper.assertTrue(BlockRules.canStandOn(Blocks.LILY_PAD.defaultBlockState()), "lily pad");
        helper.assertTrue(BlockRules.canStandOn(Blocks.CARPET.pick(DyeColor.WHITE).defaultBlockState()),
                "carpet");
        helper.assertTrue(BlockRules.canStandOn(Blocks.POTTED_CACTUS.defaultBlockState()), "potted plant");
        helper.assertTrue(BlockRules.canStandOn(Blocks.SKELETON_SKULL.defaultBlockState()), "skull");
        helper.assertTrue(BlockRules.canStandOn(Blocks.CANDLE.defaultBlockState()), "candle");

        // Upstream's one explicit exclusion: a piston head matches _HEAD by name but is not
        // something you stand on top of.
        helper.assertFalse(BlockRules.canStandOn(Blocks.PISTON_HEAD.defaultBlockState()), "piston head");
        helper.assertFalse(BlockRules.canStandOn(Blocks.AIR.defaultBlockState()), "air");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("instant_break_blocks_skip_the_crack_animation")
    static void instant_break_blocks_skip_the_crack_animation(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.TALL_GRASS.defaultBlockState()), "tall grass");
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.DEAD_BUSH.defaultBlockState()), "dead bush");
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.OAK_SAPLING.defaultBlockState()), "sapling");

        // Upstream listed WHEAT_SEEDS and BEETROOT_SEEDS, which are ITEMS — no block ever
        // matched them, so those two entries were dead. The planted blocks are WHEAT and
        // BEETROOTS, which is what this ports; the only deviation in the whole set.
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.WHEAT.defaultBlockState()), "wheat crop");
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.BEETROOTS.defaultBlockState()), "beetroot crop");

        helper.assertFalse(BlockRules.isInstantBreak(Blocks.STONE.defaultBlockState()), "stone");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("nonsolid_lists_blocks_that_look_solid_but_are_not_footing")
    static void nonsolid_lists_blocks_that_look_solid_but_are_not_footing(ExtendedGameTestHelper helper) {
        // Used by checkNearby to decide whether breaking the block under the bot's footing is
        // worth it. Excludes anything that cannot exist without support — upstream's comment.
        helper.assertTrue(BlockRules.isNonSolid(Blocks.COBWEB.defaultBlockState()), "cobweb");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.LADDER.defaultBlockState()), "ladder");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.VINE.defaultBlockState()), "vine");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.POWDER_SNOW.defaultBlockState()), "powder snow");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.WALL_TORCH.defaultBlockState()), "wall torch");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.NETHER_PORTAL.defaultBlockState()), "nether portal");

        helper.assertFalse(BlockRules.isNonSolid(Blocks.STONE.defaultBlockState()), "stone");
        helper.assertFalse(BlockRules.isNonSolid(Blocks.RAIL.defaultBlockState()),
                "rails are excluded: they cannot exist without support");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("spawn_is_what_a_bot_may_overwrite_with_cobblestone")
    static void spawn_is_what_a_bot_may_overwrite_with_cobblestone(ExtendedGameTestHelper helper) {
        // BlockScan.placeBlock and clutch both test SPAWN before putting cobblestone down.
        helper.assertTrue(BlockRules.isSpawn(Blocks.AIR.defaultBlockState()), "air");
        helper.assertTrue(BlockRules.isSpawn(Blocks.SNOW.defaultBlockState()), "snow layer");
        helper.assertTrue(BlockRules.isSpawn(Blocks.TALL_GRASS.defaultBlockState()), "tall grass");
        helper.assertTrue(BlockRules.isSpawn(Blocks.FIRE.defaultBlockState()), "fire");

        // Not water — upstream's SPAWN omits it while FALL, the near-identical set, includes
        // it. A bot will not seal itself into a pool by placing over water.
        helper.assertFalse(BlockRules.isSpawn(Blocks.WATER.defaultBlockState()),
                "water is deliberately absent from SPAWN");
        helper.assertFalse(BlockRules.isSpawn(Blocks.STONE.defaultBlockState()), "stone");

        helper.succeed();
    }

    // ---- placement predicates -----------------------------------------------

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("water_goes_on_a_plain_solid_block")
    static void water_goes_on_a_plain_solid_block(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Blocks.STONE);

        helper.assertTrue(BlockPlacement.canPlaceWater(helper.getLevel(),
                helper.absolutePos(relative), OptionalDouble.empty()), "stone takes water on top");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("water_does_not_go_on_a_dry_top_slab")
    static void water_does_not_go_on_a_dry_top_slab(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));

        // A top slab is solid, but water placed against it flows into the gap underneath
        // instead of forming a landing surface. Upstream excluded it unless already waterlogged.
        helper.assertFalse(BlockPlacement.canPlaceWater(helper.getLevel(),
                        helper.absolutePos(relative), OptionalDouble.empty()),
                "a dry top slab must be refused");

        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
                .setValue(BlockStateProperties.WATERLOGGED, true));

        helper.assertTrue(BlockPlacement.canPlaceWater(helper.getLevel(),
                        helper.absolutePos(relative), OptionalDouble.empty()),
                "a waterlogged top slab already has water and is fine");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("a_bottom_stair_depends_on_where_the_bot_is")
    static void a_bottom_stair_depends_on_where_the_bot_is(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, Blocks.STONE_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HALF, Half.BOTTOM));

        // Upstream's most obscure rule: a dry bottom-half stair is refused UNLESS the falling
        // entity's own block Y matches the stair's, in which case the bot is already inside the
        // empty upper half and the water will sit at its feet. The OptionalDouble is that
        // entity Y — absent means "no entity context", which refuses.
        helper.assertFalse(BlockPlacement.canPlaceWater(helper.getLevel(), pos, OptionalDouble.empty()),
                "no entity context: refuse");
        helper.assertFalse(BlockPlacement.canPlaceWater(helper.getLevel(), pos,
                        OptionalDouble.of(pos.getY() + 5)),
                "entity well above: refuse");
        // Exactly the block's Y, not pos.getY() + 0.5. The (int) cast truncates toward zero,
        // so at the negative Y a GameTest structure sits at, +0.5 lands on the block ABOVE.
        // That is upstream's bug, kept; the next test pins it.
        helper.assertTrue(BlockPlacement.canPlaceWater(helper.getLevel(), pos,
                        OptionalDouble.of(pos.getY())),
                "entity inside the same block: allow");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("water_goes_on_a_snow_layer_but_not_in_mid_air")
    static void water_goes_on_a_snow_layer_but_not_in_mid_air(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Blocks.SNOW);

        helper.assertTrue(BlockPlacement.canPlaceWater(helper.getLevel(),
                        helper.absolutePos(relative), OptionalDouble.empty()),
                "a snow layer is a valid landing");

        helper.assertFalse(BlockPlacement.canPlaceWater(helper.getLevel(),
                        helper.absolutePos(new BlockPos(3, 3, 3)), OptionalDouble.empty()),
                "air is not a valid landing");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("twisting_vines_need_a_surface_that_is_not_on_the_reject_list")
    static void twisting_vines_need_a_surface_that_is_not_on_the_reject_list(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.NETHERRACK);
        helper.assertTrue(BlockPlacement.canPlaceTwistingVines(helper.getLevel(),
                helper.absolutePos(new BlockPos(1, 1, 1))), "netherrack");

        helper.setBlock(new BlockPos(2, 1, 1), Blocks.OAK_FENCE);
        helper.assertFalse(BlockPlacement.canPlaceTwistingVines(helper.getLevel(),
                helper.absolutePos(new BlockPos(2, 1, 1))), "a fence is on the reject list");

        // The four that isFaceSturdy would have got wrong. All are full cubes with a solid top
        // face, so the compression an earlier draft of this plan used would have accepted them;
        // upstream rejects all four by name.
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.FARMLAND);
        helper.assertFalse(BlockPlacement.canPlaceTwistingVines(helper.getLevel(),
                helper.absolutePos(new BlockPos(3, 1, 1))), "farmland");

        helper.setBlock(new BlockPos(4, 1, 1), Blocks.HONEY_BLOCK);
        helper.assertFalse(BlockPlacement.canPlaceTwistingVines(helper.getLevel(),
                helper.absolutePos(new BlockPos(4, 1, 1))), "honey block");

        helper.setBlock(new BlockPos(5, 1, 1), Blocks.OAK_LEAVES);
        helper.assertFalse(BlockPlacement.canPlaceTwistingVines(helper.getLevel(),
                helper.absolutePos(new BlockPos(5, 1, 1))), "leaves");

        helper.setBlock(new BlockPos(1, 1, 3), Blocks.END_PORTAL_FRAME);
        helper.assertFalse(BlockPlacement.canPlaceTwistingVines(helper.getLevel(),
                helper.absolutePos(new BlockPos(1, 1, 3))), "end portal frame");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("twisting_vines_go_on_one_or_eight_snow_layers_only")
    static void twisting_vines_go_on_one_or_eight_snow_layers_only(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);

        // Upstream's rule: exactly 1 or 8. One layer leaves a full block of space above and
        // eight is a full block; anything between leaves a partial gap the vine falls into.
        for (int layers : new int[]{1, 8}) {
            helper.setBlock(relative, Blocks.SNOW.defaultBlockState()
                    .setValue(BlockStateProperties.LAYERS, layers));
            helper.assertTrue(BlockPlacement.canPlaceTwistingVines(helper.getLevel(), pos),
                    layers + " snow layers must be allowed");
        }

        for (int layers : new int[]{2, 5, 7}) {
            helper.setBlock(relative, Blocks.SNOW.defaultBlockState()
                    .setValue(BlockStateProperties.LAYERS, layers));
            helper.assertFalse(BlockPlacement.canPlaceTwistingVines(helper.getLevel(), pos),
                    layers + " snow layers must be refused");
        }

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("should_replace_takes_partial_height_blocks_only")
    static void should_replace_takes_partial_height_blocks_only(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        // Exactly the block's Y — see a_bottom_stair_depends_on_where_the_bot_is for why a
        // fractional offset does not work at negative Y.
        double insideY = pos.getY();

        // shouldReplace answers "does the water go INTO this block, or on top of it?" — it is
        // what decides between ground and ground.above() in onFallDamage. Upstream's list is
        // partial-height shapes, NOT "non-solid blocks".
        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        helper.assertTrue(BlockPlacement.shouldReplace(helper.getLevel(), pos, insideY, false),
                "a bottom slab leaves the top half free, so water goes in");

        helper.setBlock(relative, Blocks.STONE);
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, insideY, false),
                "stone is built on top of");

        // A snow layer looks like the obvious candidate and is deliberately absent from
        // upstream's list. An earlier draft of this plan asserted the opposite.
        helper.setBlock(relative, Blocks.SNOW);
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, insideY, false),
                "a snow layer is NOT in upstream's replace list");

        // Neither is a carpet, which the same draft also wrongly included.
        helper.setBlock(relative, Blocks.CARPET.pick(DyeColor.WHITE));
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, insideY, false),
                "nor is a carpet");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("the_entity_y_gate_truncates_toward_zero")
    static void the_entity_y_gate_truncates_toward_zero(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));

        // Upstream compares `(int) entityY` against a FLOORED block coordinate. Those agree
        // above y = 0 and differ by one below it, because the cast truncates toward zero:
        // (int) -58.6 is -58, while the block is -59.
        //
        // A GameTest structure sits at negative Y, so this is the real-world case. A bot
        // standing anywhere inside the block but not exactly on its boundary fails the gate,
        // which silently disables slab and stair clutches throughout the deepslate range.
        //
        // Ported faithfully. Mth.floor would fix it and would change clutch behaviour across a
        // large part of every modern world, which is a behaviour change rather than a
        // translation. This test exists so the decision is visible rather than accidental.
        helper.assertTrue(pos.getY() < 0,
                "this test only means anything below y=0, and the structure is at " + pos.getY());

        helper.assertTrue(BlockPlacement.shouldReplace(helper.getLevel(), pos, pos.getY(), false),
                "exactly on the block boundary: the gate passes");

        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, pos.getY() + 0.4, false),
                "0.4 of a block higher — still inside the same block — and the gate fails");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("should_replace_has_two_leading_gates")
    static void should_replace_has_two_leading_gates(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));

        // Gate one: the bot's own block Y must equal the block's, so a bot still well above the
        // slab builds on top of it rather than into it.
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, pos.getY() + 4, false),
                "an entity four blocks up must not replace");

        // Gate two: the Nether never replaces, because twisting vines need a surface to sit on
        // rather than a space to fill.
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, pos.getY() + 0.4, true),
                "the Nether never replaces");

        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("a_bot_stands_on_a_single_snow_layer")
    static void a_bot_stands_on_a_single_snow_layer(ExtendedGameTestHelper helper) {
        // Proof that replacing Plan A's collision-shape proxy with the real isSolid ||
        // canStandOn predicate changed something. A one-layer snow block has an EMPTY collision
        // shape — vanilla lets you walk over it without stepping up — so the old proxy rejected
        // it as footing. Upstream's canStandOn lists SNOW explicitly, so a bot should rest on it.
        BotRegistry registry = new BotRegistry();
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.SNOW);

        Bot bot = BotFactory.spawn(registry, helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3))), 0f, 0f,
                BotGameProfiles.create("SnowBot", null), false);

        for (int i = 0; i < 40 && !bot.isBotOnGround(); i++) {
            bot.tick();
        }

        helper.assertTrue(bot.isBotOnGround(), "a bot must come to rest on a snow layer");
        helper.assertFalse(bot.getStandingOn().isEmpty(),
                "and must report the snow as what it is standing on");

        bot.removeBot();
        registry.reset();
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
