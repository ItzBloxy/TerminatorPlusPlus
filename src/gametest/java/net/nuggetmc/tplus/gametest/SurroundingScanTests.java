package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.Mining;
import net.nuggetmc.tplus.agent.legacy.ScanOffset;
import net.nuggetmc.tplus.agent.legacy.SurroundingScan;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;

/**
 * Tests for the surrounding scan.
 *
 * <p>The four directional arms of upstream's {@code checkNearby} are byte-identical copies and are
 * one parameterised body here. These tests are the standing proof that the extraction is faithful:
 * one per direction, all four expecting the mirrored answer in a mirrored world. If three ever
 * pass and one fails, the deduplication is wrong and the arms go back to longhand.
 *
 * <p>Targets are bots, not mock players — {@code makeMockServerPlayer} has a null connection and
 * adding one to the level crashes the server tick. Nothing here ticks the registry, so the target
 * bot is only ever a position to face.
 */
@ForEachTest(groups = SurroundingScanTests.GROUP)
public final class SurroundingScanTests {

    public static final String GROUP = "bot.scan";

    /** Where every test puts its bot. Well clear of the template edges. */
    private static final BlockPos ORIGIN = new BlockPos(7, 1, 7);

    private SurroundingScanTests() {
    }

    private record Fixture(BotRegistry registry, SurroundingScan scan, Bot bot, Bot target) {
    }

    /**
     * A bot at {@link #ORIGIN} and a target four blocks away in {@code dir}.
     *
     * <p>The spawn yaw is deliberately not set: {@code checkNearby} opens by facing the target, so
     * the direction it scans in comes from where the target is, and a test that set the yaw would
     * be testing nothing.
     */
    private static Fixture fixture(ExtendedGameTestHelper helper, Direction dir) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        registry.setAgent(agent);

        Bot bot = spawn(helper, registry, Vec3.atBottomCenterOf(helper.absolutePos(ORIGIN)), "Scanner");
        Bot target = spawn(helper, registry,
                Vec3.atBottomCenterOf(helper.absolutePos(ORIGIN.relative(dir, 4))), "Quarry");

        SurroundingScan scan = new SurroundingScan(registry.state(), registry.agent(),
                new Mining(registry.state(), registry.agent()));

        return new Fixture(registry, scan, bot, target);
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, Vec3 at, String name) {
        Bot bot = BotFactory.spawn(registry, helper.getLevel(), at, 0f, 0f,
                BotGameProfiles.create(name, null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    /** The head-height scan finds a solid block one step ahead, in every direction. */
    private static void wallAhead(ExtendedGameTestHelper helper, Direction dir, ScanOffset expected) {
        Fixture f = fixture(helper, dir);

        helper.setBlock(ORIGIN.above().relative(dir), Blocks.STONE);

        ScanOffset result = f.scan().checkNearby(f.bot(), f.target());

        helper.assertValueEqual(f.bot().getDirection(), dir,
                "the scan must face its target before reading a direction off the bot");
        helper.assertValueEqual(result, expected, "scan result facing " + dir);

        f.registry().reset();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("scan_finds_a_wall_to_the_north")
    static void scan_finds_a_wall_to_the_north(ExtendedGameTestHelper helper) {
        wallAhead(helper, Direction.NORTH, ScanOffset.NORTH);
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("scan_finds_a_wall_to_the_south")
    static void scan_finds_a_wall_to_the_south(ExtendedGameTestHelper helper) {
        wallAhead(helper, Direction.SOUTH, ScanOffset.SOUTH);
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("scan_finds_a_wall_to_the_east")
    static void scan_finds_a_wall_to_the_east(ExtendedGameTestHelper helper) {
        wallAhead(helper, Direction.EAST, ScanOffset.EAST);
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("scan_finds_a_wall_to_the_west")
    static void scan_finds_a_wall_to_the_west(ExtendedGameTestHelper helper) {
        wallAhead(helper, Direction.WEST, ScanOffset.WEST);
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_knee_high_wall_scans_one_rung_down")
    static void a_knee_high_wall_scans_one_rung_down(ExtendedGameTestHelper helper) {
        Fixture f = fixture(helper, Direction.NORTH);

        // Knee height with something solid above it, so the walkable-step rejection does not
        // fire and the _D offset survives.
        helper.setBlock(ORIGIN.north(), Blocks.STONE);
        helper.setBlock(ORIGIN.north().above(2), Blocks.STONE);

        helper.assertValueEqual(f.scan().checkNearby(f.bot(), f.target()), ScanOffset.NORTH_D,
                "a blocked step up is mined at foot level");

        f.registry().reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_walkable_step_scans_to_nothing")
    static void a_walkable_step_scans_to_nothing(ExtendedGameTestHelper helper) {
        Fixture f = fixture(helper, Direction.NORTH);

        // The same knee-high block, but with clear air above it. Without upstream's late
        // rejection a bot mines every staircase it meets.
        helper.setBlock(ORIGIN.north(), Blocks.STONE);

        helper.assertTrue(f.scan().checkNearby(f.bot(), f.target()) == null,
                "a step the bot can walk up must scan to nothing");

        f.registry().reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_fence_two_down_scans_to_the_lowest_rung")
    static void a_fence_two_down_scans_to_the_lowest_rung(ExtendedGameTestHelper helper) {
        Fixture f = fixture(helper, Direction.NORTH);

        // A pit one deep with a fence at the bottom of it. Head and knee height ahead are clear,
        // so only the third check can find this — and it asks isFence, not blocksPath.
        helper.setBlock(ORIGIN.north(), Blocks.AIR);
        helper.setBlock(ORIGIN.north().below(), Blocks.OAK_FENCE);

        helper.assertValueEqual(f.scan().checkNearby(f.bot(), f.target()), ScanOffset.NORTH_D_2,
                "a fence two down is the lowest rung of the ladder");

        f.registry().reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_fence_in_the_bots_own_footprint_wins_over_the_wall_ahead")
    static void a_fence_in_the_bots_own_footprint_wins_over_the_wall_ahead(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        registry.setAgent(agent);

        // The bot must STRADDLE a block boundary for this path to be reachable at all, and it
        // has to straddle it on the correct side. footprintOffset measures from
        // BlockPos.containing(position) -- so at x = 8.0 exactly the bot's own block is 8, its
        // candidates are 7 and 8, and the only displacement available is -1: WEST, never EAST.
        // Just under the boundary the box still spans both columns, the bot's block is 7, and
        // the candidate at 8 is the +1 this test is about.
        Bot bot = spawn(helper, registry,
                helper.absoluteVec(new Vec3(7.95, 1, 7.5)), "Straddler");
        Bot target = spawn(helper, registry,
                Vec3.atBottomCenterOf(helper.absolutePos(ORIGIN.north(4))), "Quarry");

        // Facing north, so a fence to the east qualifies and a wall to the north does not stop
        // the footprint scan finding it first.
        helper.setBlock(ORIGIN.east(), Blocks.OAK_FENCE);
        helper.setBlock(ORIGIN.above().north(), Blocks.STONE);

        SurroundingScan scan = new SurroundingScan(registry.state(), registry.agent(),
                new Mining(registry.state(), registry.agent()));

        helper.assertValueEqual(scan.checkNearby(bot, target), ScanOffset.EAST_D,
                "the footprint fence must win, and at foot level");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_wedged_bot_scans_the_block_over_its_head")
    static void a_wedged_bot_scans_the_block_over_its_head(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        registry.setAgent(agent);

        // The last branch of the scan, and the only one that looks at the bot rather than at
        // what is in front of it: nothing ahead at any of the three heights, but the bot is
        // wedged on its own footing. A bot standing on a fence post is upstream's example --
        // the footing is one block below the bot's own block AND is a fence.
        helper.setBlock(new BlockPos(7, 0, 7), Blocks.AIR);
        helper.setBlock(new BlockPos(7, 1, 7), Blocks.OAK_FENCE);
        helper.setBlock(new BlockPos(7, 4, 7), Blocks.STONE);

        Bot bot = spawn(helper, registry,
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(7, 3, 7))), "Wedged");
        Bot target = spawn(helper, registry,
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(7, 1, 3))), "Quarry");

        // Let it settle onto the fence. A fence is 1.5 high, so the bot ends at 2.5 and its own
        // block is 2 -- one above the footing, which is what makes it "obstructed".
        for (int i = 0; i < 20; i++) {
            bot.tick();
            target.tick();
        }

        SurroundingScan scan = new SurroundingScan(registry.state(), registry.agent(),
                new Mining(registry.state(), registry.agent()));

        // With air under the fence the bot will not mine its own footing away, so the scan goes
        // for the block over its head instead.
        helper.assertValueEqual(scan.checkNearby(bot, target), ScanOffset.ABOVE,
                "a wedged bot with something overhead must mine upward; standing on "
                        + bot.getStandingOn() + " at " + bot.position());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("an_open_path_scans_to_nothing")
    static void an_open_path_scans_to_nothing(ExtendedGameTestHelper helper) {
        Fixture f = fixture(helper, Direction.NORTH);

        helper.assertTrue(f.scan().checkNearby(f.bot(), f.target()) == null,
                "nothing in the way must scan to null");

        f.registry().reset();
        helper.succeed();
    }
}
