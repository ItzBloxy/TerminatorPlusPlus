package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.TargetGoal;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.bot.EnemyTarget;

import java.util.Set;

/**
 * The ENTITY goal in a world.
 *
 * <p>Every test here calls {@code locateTarget} directly rather than ticking a registry and
 * asserting on where a bot ended up. {@code move()} adds {@code Math.random()} to every jump, so
 * a ticked test measures the walk; these measure the decision.
 *
 * <p>{@code EnemyTarget.matches} is unit tested without a world. What needs a world is the rest
 * of the branch: the entity scan, the self-exclusion, and what happens to a target that dies.
 *
 * <p><b>GameTests share a level, not just a JVM.</b> The ENTITY goal scans every living entity in
 * it and has no range limit, so a test here can see entities belonging to tests running at other
 * structure positions. Assertions must therefore be about the <i>rule</i> -- "not itself", "not the
 * cow" -- and not about the level being empty, which it never is.
 *
 * <p>Nothing here touches {@code Targeting.CUSTOM_MOB_LIST} or {@code customListMode}, which are
 * static and shared across the suite's one JVM — the ENTITY goal reads neither, which is the
 * point of keeping the two features' state apart. No {@code finally} is needed; add one the
 * moment a test here does touch them.
 */
@ForEachTest(groups = EnemyTargetTests.GROUP)
public final class EnemyTargetTests {

    public static final String GROUP = "bot.enemytarget";

    private EnemyTargetTests() {
    }

    private static BotRegistry registryWithEntityGoal() {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        agent.targeting().setTargetType(TargetGoal.ENTITY);
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

    private static LivingEntity locate(BotRegistry registry, Bot bot) {
        return ((LegacyAgent) registry.agent()).targeting().locateTarget(bot, bot.position());
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_generic_target_finds_its_type")
    static void a_generic_target_finds_its_type(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(8, 1, 5));

        bot.setEnemyTarget(EnemyTarget.ofTypes(Set.of(EntityTypes.ZOMBIE), "minecraft:zombie"));

        helper.assertTrue(locate(registry, bot) == zombie,
                "a generic zombie target must find the zombie");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_generic_target_ignores_a_type_it_does_not_name")
    static void a_generic_target_ignores_a_type_it_does_not_name(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");

        // The cow is closer. If the branch scanned without consulting the target it would win.
        Cow cow = helper.spawn(EntityTypes.COW, new BlockPos(3, 1, 5));
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(9, 1, 5));

        bot.setEnemyTarget(EnemyTarget.ofTypes(Set.of(EntityTypes.ZOMBIE), "minecraft:zombie"));

        LivingEntity found = locate(registry, bot);

        helper.assertTrue(found == zombie, "the further zombie must beat the nearer cow");
        helper.assertFalse(found == cow, "a cow is not a zombie");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_specific_target_finds_one_entity_by_id")
    static void a_specific_target_finds_one_entity_by_id(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");

        Zombie near = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(3, 1, 5));
        Zombie far = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(9, 1, 5));

        // The far one, deliberately: this is what separates `specific` from `generic`. A generic
        // zombie target would pick `near`.
        bot.setEnemyTarget(EnemyTarget.ofEntities(Set.of(far.getUUID()), "1 entities (zombie)"));

        LivingEntity found = locate(registry, bot);

        helper.assertTrue(found == far, "a specific target must find the entity it names");
        helper.assertFalse(found == near, "and not a nearer one of the same type");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_dead_specific_target_yields_no_target")
    static void a_dead_specific_target_yields_no_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(8, 1, 5));

        bot.setEnemyTarget(EnemyTarget.ofEntities(Set.of(zombie.getUUID()), "1 entities (zombie)"));
        helper.assertTrue(locate(registry, bot) == zombie, "alive, it is the target");

        zombie.kill(helper.getLevel());

        // No fallback. validateCloserEntity's isAlive() check does all of this, which is why the
        // branch adds nothing for it -- and it is what the PLAYER branch actually does, as
        // opposed to what PLAYER's enum description claims.
        helper.assertTrue(locate(registry, bot) == null, "dead, there is no target at all");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_bot_does_not_target_itself")
    static void a_bot_does_not_target_itself(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");

        // A rival, because "found nothing" is not the assertion to make here: the level is shared
        // with every other running test, so `generic player` will find someone regardless. What
        // matters is that it is never the hunter -- which sits at distance 0 and would therefore
        // win every comparison in validateCloserEntity if the bot != entity guard were dropped.
        spawn(helper, registry, new BlockPos(9, 1, 5), "Rival");

        // Bots are ServerPlayers, so `generic player` reaches them. That is intended for other
        // bots and absurd for this one.
        hunter.setEnemyTarget(EnemyTarget.ofTypes(Set.of(EntityTypes.PLAYER), "minecraft:player"));

        LivingEntity found = locate(registry, hunter);

        helper.assertTrue(found != null, "the scan must find some player -- the rival at worst");
        helper.assertFalse(found == hunter, "but never the hunter itself");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("another_bot_is_a_valid_target")
    static void another_bot_is_a_valid_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        Bot quarry = spawn(helper, registry, new BlockPos(8, 1, 5), "Quarry");

        // The other half of the self-exclusion: only self is excluded, not every bot.
        hunter.setEnemyTarget(EnemyTarget.ofEntities(Set.of(quarry.getUUID()), "1 entities (player)"));

        helper.assertTrue(locate(registry, hunter) == quarry,
                "a specific target must be able to name another bot");

        registry.reset();
        helper.succeed();
    }
}
