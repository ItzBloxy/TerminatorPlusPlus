package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.BlockScan;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.Mining;
import net.nuggetmc.tplus.agent.legacy.TargetGoal;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.event.BotDeathEvent;
import net.nuggetmc.tplus.event.TerminatorLocateTargetEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * In-world tests for the agent.
 *
 * <p>These drive the registry's tick directly rather than waiting on the server tick, so a test
 * controls exactly how many agent ticks have happened. That matters: almost everything the agent
 * does is gated on {@code tickDelay}, and a test that just waits is testing the scheduler.
 *
 * <p><b>Targets are bots, not mock players.</b> {@code makeMockServerPlayer} has a null
 * connection, so adding one to the level crashes the server in the world tick — see
 * {@code BotCombatTests} for the full story. A second bot is level-resident, has a working
 * {@code BotConnection}, and is found by the {@code NEAREST_BOT} goal through the registry.
 * {@code makeMockServerPlayerInLevel} is the alternative when the player-scanning path itself is
 * what is under test; it goes through {@code placeNewPlayer}, and it hardcodes CREATIVE, so it
 * only works with a goal that ignores gamemode.
 */
@ForEachTest(groups = AgentTests.GROUP)
public final class AgentTests {

    public static final String GROUP = "bot.agent";

    private AgentTests() {
    }

    private static BotRegistry registryWithAgent(TargetGoal goal) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        agent.targeting().setTargetType(goal);
        registry.setAgent(agent);
        return registry;
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry,
                             BlockPos relative, String name) {
        Bot bot = BotFactory.spawn(registry, helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(relative)), 0f, 0f,
                BotGameProfiles.create(name, null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    /**
     * Ticks the bots only, with no agent.
     *
     * <p>Two things need draining before an agent test is deterministic: a fresh ServerPlayer
     * carries {@code invulnerableTime = 60}, so nothing can hurt it for the first 56 ticks, and
     * a freshly spawned bot is not yet grounded, so {@code tickBot}'s grounded branch never
     * runs. Doing both here rather than inside the agent window keeps the bots where the test
     * put them — on NEAREST_BOT they hunt each other, so a long agent window makes their
     * relative geometry unpredictable.
     */
    private static void settle(BotRegistry registry, int ticks) {
        for (int i = 0; i < ticks; i++) {
            for (Bot bot : registry.bots()) {
                bot.tick();
            }
        }
    }

    /** Drives the registry and every bot in it, so physics and the agent stay in step. */
    private static void run(BotRegistry registry, int ticks) {
        for (int i = 0; i < ticks; i++) {
            registry.tick();
            for (Bot bot : registry.bots()) {
                bot.tick();
            }
        }
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_closes_the_distance_to_its_target")
    static void a_bot_closes_the_distance_to_its_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 7), "Hunter");
        Bot quarry = spawn(helper, registry, new BlockPos(12, 1, 7), "Quarry");

        double before = hunter.position().distanceTo(quarry.position());
        run(registry, 100);
        double after = hunter.position().distanceTo(quarry.position());

        // Both bots hunt each other on NEAREST_BOT, which only makes the gap close faster.
        helper.assertTrue(after < before - 1.0,
                "the bots must converge; was " + before + ", now " + after);

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_turns_to_face_its_target")
    static void a_bot_turns_to_face_its_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 7), "Hunter");
        spawn(helper, registry, new BlockPos(13, 1, 7), "Quarry");

        // Ground them first, then run the agent only briefly: both bots hunt each other on
        // NEAREST_BOT, and after twenty ticks of converging their relative bearing is anyone's
        // guess. Twelve blocks apart and six agent ticks keeps it unambiguous.
        settle(registry, 5);
        run(registry, 6);

        // resetHand and move both call faceLocation on the target's TRUE position, not the
        // offset aim point, so this is due east — yaw 270 the way BotMath normalises.
        float off = Math.abs(Mth.wrapDegrees(hunter.getYRot() - 270f));
        helper.assertTrue(off < 30f,
                "the bot should be facing roughly east, yaw is " + hunter.getYRot());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_damages_a_target_in_range")
    static void a_bot_damages_a_target_in_range(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        spawn(helper, registry, new BlockPos(7, 1, 7), "Hunter");
        Bot quarry = spawn(helper, registry, new BlockPos(8, 1, 7), "Quarry");

        // Drain the 60-tick spawn invulnerability with the agent switched off. Doing it inside
        // the agent window instead means the bots spend those ticks jumping into each other,
        // and by the time either can be hurt they have knocked themselves out of the 4-block
        // range that LegacyAgent.attack requires.
        settle(registry, 70);

        float before = quarry.getHealth();
        run(registry, 12);

        helper.assertTrue(quarry.getHealth() < before,
                "a bot next to its target must hurt it; health went " + before
                        + " -> " + quarry.getHealth());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("the_none_goal_stops_the_bot_hunting")
    static void the_none_goal_stops_the_bot_hunting(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NONE);
        Bot hunter = spawn(helper, registry, new BlockPos(7, 1, 7), "Hunter");
        Bot quarry = spawn(helper, registry, new BlockPos(8, 1, 7), "Quarry");

        float before = quarry.getHealth();
        Vec3 start = hunter.position();
        run(registry, 60);

        helper.assertValueEqual(quarry.getHealth(), before, "the NONE goal must not attack");
        helper.assertTrue(hunter.position().distanceTo(start) < 1.0,
                "with no target the bot should stay put");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_disabled_agent_does_nothing")
    static void a_disabled_agent_does_nothing(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        registry.agent().setEnabled(false);

        spawn(helper, registry, new BlockPos(7, 1, 7), "Hunter");
        Bot quarry = spawn(helper, registry, new BlockPos(8, 1, 7), "Quarry");

        float before = quarry.getHealth();
        run(registry, 60);

        helper.assertValueEqual(quarry.getHealth(), before, "a disabled agent must not attack");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_in_water_swims_toward_its_target")
    static void a_bot_in_water_swims_toward_its_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);

        // A 3x2x3 pool, so the bot is in water with water below it: that is the `anim` case
        // in swim, the one that sets the swimming pose.
        for (int x = 1; x <= 3; x++) {
            for (int z = 6; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
            }
        }

        Bot swimmer = spawn(helper, registry, new BlockPos(2, 2, 7), "Swimmer");
        spawn(helper, registry, new BlockPos(12, 1, 7), "Quarry");

        run(registry, 40);

        helper.assertTrue(swimmer.getVelocity().length() > 0.0,
                "a bot in water must be pushed toward its target");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("the_centring_check_notices_a_stuck_bot")
    static void the_centring_check_notices_a_stuck_bot(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NONE);
        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7), "Stuck");

        // No target, so nothing moves the bot. center() runs every 20 ticks and compares the
        // block column against the previous sample, so after two samples it must report the
        // bot as stationary. sameXZ is what later unlocks checkUp and checkSide, and it runs
        // BEFORE the no-target return — which is easy to get wrong when inserting the
        // remaining checks.
        run(registry, 45);

        helper.assertTrue(Boolean.TRUE.equals(registry.state().btCheck.get(bot)),
                "a bot that has not moved must be flagged as same-column");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bus_handler_can_veto_a_target")
    static void a_bus_handler_can_veto_a_target(ExtendedGameTestHelper helper) {
        // TerminatorLocateTargetEvent is the only extension point this port keeps on the
        // NeoForge bus, and it is the one thing a third-party mod can use to steer bots. It
        // had no coverage at all.
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 7), "Hunter");
        spawn(helper, registry, new BlockPos(12, 1, 7), "Quarry");

        VetoHandler handler = new VetoHandler();
        NeoForge.EVENT_BUS.register(handler);

        try {
            Vec3 start = hunter.position();
            settle(registry, 5);
            run(registry, 40);

            helper.assertTrue(handler.seen > 0, "the event must actually be posted");
            helper.assertTrue(hunter.position().distanceTo(start) < 1.0,
                    "a cancelled event means no target, so the bot must not move");
        } finally {
            NeoForge.EVENT_BUS.unregister(handler);
        }

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bus_handler_can_retarget_a_bot")
    static void a_bus_handler_can_retarget_a_bot(ExtendedGameTestHelper helper) {
        // Cancelling and retargeting are different: a handler that sets a target replaces the
        // goal's choice, where cancelling means "no target this tick".
        //
        // The goal is NEAREST_HOSTILE and the arena has no hostiles, so the goal itself finds
        // nothing and the event carries a null target — which is exactly the case the event's
        // contract calls out, a handler supplying a target the goal would never have picked.
        // NONE would NOT work here: it returns before the event is posted at all, which the
        // next test pins.
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_HOSTILE);
        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 7), "Hunter");
        Bot quarry = spawn(helper, registry, new BlockPos(12, 1, 7), "Quarry");

        RetargetHandler handler = new RetargetHandler(quarry);
        NeoForge.EVENT_BUS.register(handler);

        try {
            double before = hunter.position().distanceTo(quarry.position());
            settle(registry, 5);
            run(registry, 60);
            double after = hunter.position().distanceTo(quarry.position());

            helper.assertTrue(after < before - 1.0,
                    "the handler's target must be chased even though the goal found nothing; "
                            + "was " + before + ", now " + after);
        } finally {
            NeoForge.EVENT_BUS.unregister(handler);
        }

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("the_none_goal_does_not_post_the_locate_event")
    static void the_none_goal_does_not_post_the_locate_event(ExtendedGameTestHelper helper) {
        // Upstream's switch has `default: return null;` covering NONE, and that return happens
        // BEFORE the event is constructed. So NONE is not "target nothing, then ask the bus" —
        // it is "do not ask the bus at all", and a handler cannot revive a NONE-goal bot.
        // Faithful, and surprising enough that an earlier draft of this very test assumed the
        // opposite and failed.
        BotRegistry registry = registryWithAgent(TargetGoal.NONE);
        spawn(helper, registry, new BlockPos(1, 1, 7), "Hunter");
        spawn(helper, registry, new BlockPos(12, 1, 7), "Quarry");

        VetoHandler handler = new VetoHandler();
        NeoForge.EVENT_BUS.register(handler);

        try {
            settle(registry, 5);
            run(registry, 30);

            helper.assertValueEqual(handler.seen, 0,
                    "the NONE goal must not post TerminatorLocateTargetEvent at all");
        } finally {
            NeoForge.EVENT_BUS.unregister(handler);
        }

        registry.reset();
        helper.succeed();
    }

    // ---- mining -------------------------------------------------------------

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("mining_starts_exactly_one_swing_animation_per_bot")
    static void mining_starts_exactly_one_swing_animation_per_bot(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot bot = spawn(helper, registry, new BlockPos(6, 1, 7), "Miner");
        spawn(helper, registry, new BlockPos(10, 1, 7), "Quarry");

        // Bedrock, because it has to still be there at the end. Hardness is irrelevant to this
        // agent — every block takes the same twenty ticks — so obsidian would be gone by tick
        // 22 and resetHand would have cancelled the animation on tick 23.
        helper.setBlock(new BlockPos(6, 2, 7), Blocks.BEDROCK);

        settle(registry, 5);
        run(registry, 60);

        // preBreak runs every tick while the bot is blocked, and the containsKey guard is the
        // only thing stopping it stacking one swing task per tick.
        helper.assertTrue(registry.state().miningAnim.containsKey(bot),
                "an animation must be running");
        helper.assertValueEqual(registry.state().miningAnim.size(), 1,
                "exactly one animation, not sixty");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_blocked_bot_equips_the_right_tool")
    static void a_blocked_bot_equips_the_right_tool(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot bot = spawn(helper, registry, new BlockPos(6, 1, 7), "Miner");
        spawn(helper, registry, new BlockPos(10, 1, 7), "Quarry");

        // A block at head height makes checkAt fire, and a pickaxe is the fastest of the three
        // tools upstream considers against obsidian. Not bedrock: nothing is mineable with a
        // bedrock, so no tool beats the bare hand and optimalTool returns an empty stack.
        helper.setBlock(new BlockPos(6, 2, 7), Blocks.OBSIDIAN);

        // Well inside the twenty ticks the progress task needs, so the obsidian is still there
        // and the bot is still holding the tool it chose for it.
        settle(registry, 5);
        run(registry, 10);

        helper.assertTrue(bot.getMainHandItem().is(Items.IRON_PICKAXE),
                "a bot mining stone must hold the pickaxe, got " + bot.getMainHandItem());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_inside_a_fence_stops_to_break_it")
    static void a_bot_inside_a_fence_stops_to_break_it(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);

        helper.setBlock(new BlockPos(6, 1, 7), Blocks.OAK_FENCE);
        Bot bot = spawn(helper, registry, new BlockPos(6, 1, 7), "Fenced");
        spawn(helper, registry, new BlockPos(12, 1, 7), "Quarry");

        settle(registry, 5);
        Vec3 start = bot.position();

        // Short of the twenty ticks the progress task needs, so the fence is still standing and
        // this is only about where the bot is: checkFenceAndGates returns "handled", so tickBot
        // stops before move().
        run(registry, 12);

        helper.assertTrue(registry.state().miningAnim.containsKey(bot),
                "the bot must be mining the fence rather than jumping at it");
        helper.assertTrue(bot.position().distanceTo(start) < 2.0,
                "and must not have wandered off toward the target");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_breaks_the_block_in_its_head_space")
    static void a_bot_breaks_the_block_in_its_head_space(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        spawn(helper, registry, new BlockPos(6, 1, 7), "Miner");
        spawn(helper, registry, new BlockPos(10, 1, 7), "Quarry");

        BlockPos head = new BlockPos(6, 2, 7);
        helper.setBlock(head, Blocks.STONE);

        settle(registry, 5);

        // Ten stages two ticks apart, plus the tick checkAt needs to notice the block. The
        // progress task runs off the registry's scheduler, so it only advances inside run().
        run(registry, 45);

        helper.assertBlockPresent(Blocks.AIR, head);
        helper.assertTrue(registry.state().crackList.isEmpty(),
                "the crack overlay must be cleared once the block is gone");
        helper.assertTrue(registry.state().mining.isEmpty(),
                "and the progress entry forgotten");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_breaks_the_fence_it_is_standing_in")
    static void a_bot_breaks_the_fence_it_is_standing_in(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);

        BlockPos fence = new BlockPos(6, 1, 7);
        helper.setBlock(fence, Blocks.OAK_FENCE);
        spawn(helper, registry, fence, "Fenced");
        spawn(helper, registry, new BlockPos(12, 1, 7), "Quarry");

        settle(registry, 5);
        run(registry, 45);

        // The AT_D path: the block being broken is the one the bot is standing in, so
        // currentTarget resolves through the offset rather than through getStandingOn.
        helper.assertBlockPresent(Blocks.AIR, fence);

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("losing_its_target_stops_the_swing_animation")
    static void losing_its_target_stops_the_swing_animation(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot bot = spawn(helper, registry, new BlockPos(6, 1, 7), "Miner");
        Bot quarry = spawn(helper, registry, new BlockPos(10, 1, 7), "Quarry");

        helper.setBlock(new BlockPos(6, 2, 7), Blocks.BEDROCK);

        settle(registry, 5);
        run(registry, 20);
        helper.assertTrue(registry.state().miningAnim.containsKey(bot),
                "the bot must be mining before the target goes away");

        // Check 4: tickBot gives up the moment locateTarget comes back null, and the swing has
        // to stop with it — nothing else cancels it on that path.
        registry.remove(quarry);
        run(registry, 5);

        helper.assertTrue(registry.state().miningAnim.isEmpty(),
                "a bot with nothing to hunt must not keep swinging");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("place_water_down_dumps_the_bucket_and_picks_it_up")
    static void place_water_down_dumps_the_bucket_and_picks_it_up(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot bot = spawn(helper, registry, new BlockPos(6, 1, 7), "Firefighter");

        // Its own Mining over the registry's state and agent: placeWaterDown has no call site
        // until task 22 wires miscellaneousChecks, and an accessor added only for a test would
        // outlive the reason for it.
        Mining mining = new Mining(registry.state(), registry.agent());

        BlockPos at = helper.absolutePos(new BlockPos(6, 3, 7));
        mining.placeWaterDown(bot, at);

        helper.assertBlockPresent(Blocks.WATER, new BlockPos(6, 3, 7));
        helper.assertTrue(bot.getMainHandItem().is(Items.BUCKET),
                "the bucket must be empty while the water is down, got " + bot.getMainHandItem());

        // Five ticks later it picks the water back up. The scheduler only advances inside
        // registry.tick(), so this is exactly five agent ticks and not a wall-clock wait.
        run(registry, 5);

        helper.assertBlockPresent(Blocks.AIR, new BlockPos(6, 3, 7));
        helper.assertTrue(bot.getMainHandItem().is(Items.WATER_BUCKET),
                "and full again afterwards, got " + bot.getMainHandItem());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("down_mine_in_water_dives_and_suppresses_fall_damage")
    static void down_mine_in_water_dives_and_suppresses_fall_damage(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Mining mining = new Mining(registry.state(), registry.agent());

        BlockPos floor = new BlockPos(6, 1, 7);
        helper.setBlock(floor, Blocks.WATER);
        Bot bot = spawn(helper, registry, floor, "Diver");
        settle(registry, 5);

        mining.downMine(bot, helper.absolutePos(floor));

        // The water branch is not an else: it runs after the centring nudge and overwrites its
        // velocity, so Y ends at exactly -1 however far off-centre the bot is.
        helper.assertTrue(bot.getVelocity().getY() == -1.0,
                "a bot in water dives at one block a tick, got " + bot.getVelocity().getY());
        helper.assertTrue(registry.state().fallDamageCooldown.contains(bot),
                "and must be exempt from fall damage while it does");

        run(registry, 10);

        helper.assertTrue(!registry.state().fallDamageCooldown.contains(bot),
                "the exemption lasts ten ticks and no longer");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("stopping_the_agent_frees_the_block_it_was_mining")
    static void stopping_the_agent_frees_the_block_it_was_mining(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        spawn(helper, registry, new BlockPos(6, 1, 7), "Miner");
        spawn(helper, registry, new BlockPos(10, 1, 7), "Quarry");

        BlockPos head = new BlockPos(6, 2, 7);
        helper.setBlock(head, Blocks.STONE);

        settle(registry, 5);
        run(registry, 10);

        helper.assertTrue(!registry.state().crackList.isEmpty(), "a break must be under way");

        // LegacyAgent overrides stopAllTasks to clear the overlays the cancelled tasks were
        // drawing. Without that override the crackList entry survives its task, and
        // blockBreakEffect reads a surviving entry as "some bot is already mining this" -- so
        // the block below would never break again, however long the bot stood there.
        registry.agent().stopAllTasks();

        helper.assertTrue(registry.state().crackList.isEmpty(), "the crack list must be cleared");
        helper.assertTrue(registry.state().mining.isEmpty(), "and the progress map with it");

        run(registry, 45);
        helper.assertBlockPresent(Blocks.AIR, head);

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("removing_a_bot_cancels_its_swing_animation")
    static void removing_a_bot_cancels_its_swing_animation(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot bot = spawn(helper, registry, new BlockPos(6, 1, 7), "Miner");
        spawn(helper, registry, new BlockPos(10, 1, 7), "Quarry");

        helper.setBlock(new BlockPos(6, 2, 7), Blocks.BEDROCK);
        settle(registry, 5);
        run(registry, 10);

        Integer anim = registry.state().miningAnim.get(bot);
        helper.assertTrue(anim != null, "the bot must be swinging before it is removed");

        // The id has to be read before remove(), because remove() is what drops it. That is the
        // whole point: forgetting the entry without cancelling first leaves a repeating task
        // punching a removed bot forever.
        registry.remove(bot);

        helper.assertTrue(registry.scheduler().isCancelled(anim),
                "removing a mining bot must cancel its animation, not just forget the id");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("bedrock_is_never_broken")
    static void bedrock_is_never_broken(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        spawn(helper, registry, new BlockPos(6, 1, 7), "Miner");
        spawn(helper, registry, new BlockPos(10, 1, 7), "Quarry");

        BlockPos head = new BlockPos(6, 2, 7);
        helper.setBlock(head, Blocks.BEDROCK);

        settle(registry, 5);
        run(registry, 60);

        helper.assertBlockPresent(Blocks.BEDROCK, head);

        // Upstream returns without advancing rather than cancelling, so after sixty ticks the
        // task is still registered, still on stage zero, and still costing a tick every two.
        // Faithfully wasteful — pinned here so the waste is not mistaken for a bug later.
        helper.assertTrue(!registry.state().crackList.isEmpty(),
                "the progress task must still be running on the bedrock");

        registry.reset();
        helper.succeed();
    }

    // ---- vertical navigation ------------------------------------------------

    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "9x30x9", floor = true)
    @TestHolder("a_bot_towers_toward_a_target_above_it")
    static void a_bot_towers_toward_a_target_above_it(ExtendedGameTestHelper helper) {
        // A real player rather than a second bot: the target has to stay exactly where it is
        // put, and a bot would hunt back. makeMockServerPlayerInLevel is CREATIVE, so the goal
        // has to be the one that ignores gamemode.
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_PLAYER);

        var target = helper.makeMockServerPlayerInLevel();
        target.snapTo(helper.absoluteVec(new Vec3(4.5, 12, 4.5)), 0f, 0f);

        Bot bot = spawn(helper, registry, new BlockPos(4, 1, 4), "Climber");

        double startY = bot.getY();
        run(registry, 300);

        // checkUp needs the same column and three clear blocks above the bot's feet. It then
        // places cobblestone where the feet were and jumps off it.
        helper.assertTrue(bot.getY() > startY + 1.0,
                "the bot must climb; y went " + startY + " -> " + bot.getY());

        // And it must have climbed by building, not by bouncing: the first tower step places
        // cobblestone where the bot's feet were.
        helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(4, 1, 4));

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "9x30x9", floor = true)
    @TestHolder("a_bot_mines_down_toward_a_target_far_below")
    static void a_bot_mines_down_toward_a_target_far_below(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_PLAYER);

        // A solid column under the bot, so neither ray reaches the target and checkDown is the
        // only way down.
        for (int y = 2; y <= 14; y++) {
            for (int x = 3; x <= 5; x++) {
                for (int z = 3; z <= 5; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }

        var target = helper.makeMockServerPlayerInLevel();
        target.snapTo(helper.absoluteVec(new Vec3(4.5, 1, 4.5)), 0f, 0f);

        Bot bot = spawn(helper, registry, new BlockPos(4, 15, 4), "Digger");

        double startY = bot.getY();
        run(registry, 200);

        // Thirteen blocks down and out of sight: same column and more than one block up is the
        // first of checkDown's two ways in.
        helper.assertTrue(bot.getY() < startY - 1.0,
                "the bot must dig down; y went " + startY + " -> " + bot.getY());

        // Down through the column, not around it.
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 14, 4));

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x30x9", floor = true)
    @TestHolder("placing_a_block_over_nothing_also_fills_the_block_below")
    static void placing_a_block_over_nothing_also_fills_the_block_below(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot bot = spawn(helper, registry, new BlockPos(4, 1, 4), "Mason");
        BlockScan blockScan = new BlockScan(registry.state(), registry.agent());

        // Mid-air, with nothing within a block of it in any direction. The first branch shores
        // up the block below and then -- upstream's missing return -- falls through every
        // neighbour test to place the target block as well, both in the same tick.
        BlockPos at = new BlockPos(4, 10, 4);
        blockScan.placeBlock(bot, helper.absolutePos(at));

        helper.assertBlockPresent(Blocks.COBBLESTONE, at);
        helper.assertBlockPresent(Blocks.COBBLESTONE, at.below());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x30x9", floor = true)
    @TestHolder("placing_a_block_on_solid_ground_leaves_the_ground_alone")
    static void placing_a_block_on_solid_ground_leaves_the_ground_alone(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);
        Bot bot = spawn(helper, registry, new BlockPos(4, 1, 4), "Mason");
        BlockScan blockScan = new BlockScan(registry.state(), registry.agent());

        BlockPos at = new BlockPos(4, 10, 4);
        helper.setBlock(at.below(), Blocks.STONE);

        blockScan.placeBlock(bot, helper.absolutePos(at));

        helper.assertBlockPresent(Blocks.COBBLESTONE, at);
        helper.assertBlockPresent(Blocks.STONE, at.below());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "9x30x9", floor = true)
    @TestHolder("a_falling_bot_clutches_with_water")
    static void a_falling_bot_clutches_with_water(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);

        // A target at the bottom so the agent has something to chase, and the falling bot high
        // above it. onFallDamage is what has to save it: find the floor, place water, cancel
        // the damage, and pick the water back up five ticks later.
        spawn(helper, registry, new BlockPos(4, 1, 4), "Quarry");
        Bot faller = spawn(helper, registry, new BlockPos(4, 25, 4), "Faller");

        float before = faller.getHealth();
        run(registry, 150);

        helper.assertTrue(faller.isAlive(), "the bot must survive a 24-block fall");
        helper.assertValueEqual(faller.getHealth(), before, "and take no fall damage");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("drops_disabled_clears_the_drop_list")
    static void drops_disabled_clears_the_drop_list(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NONE);
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3), "Dropper");

        List<ItemEntity> drops = new ArrayList<>();
        drops.add(new ItemEntity(helper.getLevel(), bot.getX(), bot.getY(), bot.getZ(),
                new ItemStack(Items.DIAMOND)));

        // drops defaults to false, which is upstream's Agent field default, so a bot drops
        // nothing until /tplus drops true. The handler is what clears the staged collection.
        helper.assertFalse(registry.agent().isDrops(), "drops must default to disabled");

        registry.agent().onBotDeath(new BotDeathEvent(bot, helper.getLevel().damageSources().fall(), drops));
        helper.assertTrue(drops.isEmpty(), "with drops disabled the list must be cleared");

        registry.agent().setDrops(true);
        drops.add(new ItemEntity(helper.getLevel(), bot.getX(), bot.getY(), bot.getZ(),
                new ItemStack(Items.DIAMOND)));

        registry.agent().onBotDeath(new BotDeathEvent(bot, helper.getLevel().damageSources().fall(), drops));
        helper.assertValueEqual(drops.size(), 1, "with drops enabled the list must survive");

        bot.removeBot();
        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_finds_a_real_player_through_the_player_list")
    static void a_bot_finds_a_real_player_through_the_player_list(ExtendedGameTestHelper helper) {
        // The other tests use NEAREST_BOT, which scans the registry. This one covers the
        // player-scanning path end to end, which is what the default goal uses.
        // makeMockServerPlayerInLevel goes through placeNewPlayer, so the player really is in
        // the PlayerList — and it hardcodes CREATIVE, so the goal has to be one that ignores
        // gamemode.
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_PLAYER);

        var player = helper.makeMockServerPlayerInLevel();
        player.snapTo(helper.absoluteVec(new Vec3(12.5, 1, 7.5)), 0f, 0f);

        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 7), "Hunter");

        double before = hunter.position().distanceTo(player.position());
        run(registry, 100);
        double after = hunter.position().distanceTo(player.position());

        helper.assertTrue(after < before - 1.0,
                "the bot must close on a real player; was " + before + ", now " + after);

        registry.reset();
        helper.succeed();
    }
}

/** Vetoes every target the goal picks, and counts the events it saw. */
final class VetoHandler {

    int seen;

    @SubscribeEvent
    public void onLocate(TerminatorLocateTargetEvent event) {
        seen++;
        event.setCanceled(true);
    }
}

/** Replaces whatever the goal picked with a fixed entity. */
final class RetargetHandler {

    private final LivingEntity target;

    RetargetHandler(LivingEntity target) {
        this.target = target;
    }

    @SubscribeEvent
    public void onLocate(TerminatorLocateTargetEvent event) {
        if (event.getBot() != target) {
            event.setTarget(target);
        }
    }
}
