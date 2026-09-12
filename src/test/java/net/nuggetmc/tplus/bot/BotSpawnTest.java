package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Intended to exercise the NMS layer against a real headless server: the fake
 * connection, both spawn paths, physics, and cleanup on removal. Spec risk 1 — bots in
 * the real PlayerList — is what this suite exists to settle.
 *
 * <h2>Why this is disabled</h2>
 *
 * <p>Plan A assumed {@link EphemeralTestServerProvider} could host these. It cannot.
 * That server has <strong>no levels at all</strong>: its provider builds a frozen, empty
 * {@code LevelStem} registry with the comment "The server doesn't have any levels", and
 * its own javadoc says not to touch the world and to use a GameTest if you need one.
 * Every test here needs a {@code ServerLevel} to construct a {@code Bot}, so all 16
 * failed with "overworld must exist".
 *
 * <p>Kept rather than deleted, and kept compiling, so it stays honest against API drift
 * and serves as the specification for the GameTest conversion. The bodies are already
 * close: each needs a {@code ServerLevel} from an {@code ExtendedGameTestHelper} instead
 * of {@code server.getLevel(OVERWORLD)}.
 *
 * <p>Converting requires the NeoForge testframework harness —
 * {@code FrameworkConfiguration.builder(...).create()} wired into the mod, plus
 * {@code @ForEachTest}/{@code @TestHolder}/{@code @GameTest}/{@code @EmptyTemplate}
 * holders, and moving {@code testframework} off {@code testImplementation} because
 * GameTests run in-game. That is deliberately not being done on the fly; see the
 * Task 12 record.
 *
 * <p>Until then the same ground is covered manually over RCON, which did catch two real
 * bugs (the empty-VoxelShape crash and the missing tick isolation).
 */
@Disabled("EphemeralTestServerProvider has no levels; needs converting to NeoForge GameTests - see class javadoc")
@ExtendWith(EphemeralTestServerProvider.class)
class BotSpawnTest {

    /** Well above generated terrain, so a placed floor is never buried in a hill. */
    private static final int TEST_Y = 200;

    private static ServerLevel overworld(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        assertNotNull(level, "overworld must exist");
        return level;
    }

    private static Bot spawn(BotRegistry registry, MinecraftServer server, Vec3 pos, boolean playerList) {
        GameProfile profile = BotGameProfiles.create("TestBot", null);
        return BotFactory.spawn(registry, overworld(server), pos, 0f, 0f, profile, playerList);
    }

    /** Places a floor block, clears the column above it, and drops a bot in. */
    private static Bot spawnAbove(BotRegistry registry, MinecraftServer server,
                                  BlockPos floor, BlockState floorState, double height) {
        ServerLevel level = overworld(server);

        for (int dy = 1; dy <= 50; dy++) {
            level.setBlock(floor.above(dy), Blocks.AIR.defaultBlockState(), 2);
        }
        level.setBlock(floor, floorState, 2);

        Vec3 pos = new Vec3(floor.getX() + 0.5, floor.getY() + height, floor.getZ() + 0.5);
        GameProfile profile = BotGameProfiles.create("GroundBot", null);

        return BotFactory.spawn(registry, level, pos, 0f, 0f, profile, false);
    }

    private static void tickUntilGrounded(Bot bot, int maxTicks) {
        for (int i = 0; i < maxTicks && !bot.isBotOnGround(); i++) {
            bot.tick();
        }
    }

    // ---- spawn paths -------------------------------------------------------

    @Test
    void spawnsAsAFreshEntityWithoutJoiningThePlayerList(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), false);

        assertTrue(bot.isAlive());
        assertFalse(bot.isInPlayerList());
        assertFalse(server.getPlayerList().getPlayers().contains(bot));
        assertSame(bot, overworld(server).getEntity(bot.getId()),
                "bot must be a real entity in the level");

        bot.removeBot();
    }

    @Test
    void spawnsIntoThePlayerListWhenAsked(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), true);

        assertTrue(bot.isInPlayerList());
        assertTrue(server.getPlayerList().getPlayers().contains(bot));

        bot.removeBot();
        assertFalse(server.getPlayerList().getPlayers().contains(bot),
                "removeBot must take the bot back out of the player list");
    }

    @Test
    void spawningRegistersTheBotAndSetsItsOwner(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), false);

        assertEquals(1, registry.size(), "BotFactory.spawn must register the bot");
        assertSame(registry, bot.getRegistry(),
                "the bot must point at the registry that owns it, not a global one");

        bot.removeBot();
    }

    // ---- the fake connection ----------------------------------------------

    @Test
    void tickingDoesNotThrowThroughTheFakeConnection(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), false);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 20; i++) {
                bot.tick();
            }
        }, "20 ticks must not throw through the fake connection");

        bot.removeBot();
    }

    @Test
    void tickingAPlayerListBotDoesNotThrow(MinecraftServer server) {
        // The riskier path: the server may try to send this bot packets.
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), true);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 20; i++) {
                bot.tick();
            }
        });

        bot.removeBot();
    }

    // ---- physics -----------------------------------------------------------

    @Test
    void anUnsupportedBotFallsUnderGravity(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), false);
        double startY = bot.getY();

        for (int i = 0; i < 10; i++) {
            bot.tick();
        }

        assertTrue(bot.getY() < startY, "bot should have fallen; y went " + startY + " -> " + bot.getY());

        bot.removeBot();
    }

    @Test
    void botLandsOnASolidFloor(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawnAbove(registry, server, new BlockPos(64, TEST_Y, 64),
                Blocks.STONE.defaultBlockState(), 4);

        tickUntilGrounded(bot, 60);

        assertTrue(bot.isBotOnGround(), "bot never landed; y=" + bot.getY());
        assertFalse(bot.getStandingOn().isEmpty(), "standingOn was empty while on the ground");

        bot.removeBot();
    }

    @Test
    void botStandsOnAFence(MinecraftServer server) {
        // Exercises the BlockTags.FENCES branch of GroundCheck via typeHolder().is(...),
        // which needs a loaded datapack and so cannot be unit tested.
        BotRegistry registry = new BotRegistry();
        Bot bot = spawnAbove(registry, server, new BlockPos(70, TEST_Y, 70),
                Blocks.OAK_FENCE.defaultBlockState(), 4);

        tickUntilGrounded(bot, 60);

        assertTrue(bot.isBotOnGround(), "bot did not come to rest on the fence; y=" + bot.getY());

        bot.removeBot();
    }

    @Test
    void botStandsOnAGlassPane(MinecraftServer server) {
        // LegacyMats.FENCE included GLASS_PANE and IRON_BARS, which is why the fence
        // pass consults BlockTags.BARS. Regression guard for that being dropped.
        BotRegistry registry = new BotRegistry();
        Bot bot = spawnAbove(registry, server, new BlockPos(82, TEST_Y, 82),
                Blocks.GLASS_PANE.defaultBlockState(), 4);

        tickUntilGrounded(bot, 60);

        assertTrue(bot.isBotOnGround(), "bot did not come to rest on the glass pane; y=" + bot.getY());

        bot.removeBot();
    }

    @Test
    void botStandsOnALadder(MinecraftServer server) {
        // Regression guard for the shape bug found auditing Tasks 1-10: a ladder has a
        // non-empty OUTLINE shape but an EMPTY COLLISION shape. Reading the collision
        // shape made GroundCheck drop it, so a bot fell straight through every block
        // LegacyMats.canStandOn allowed. Bukkit's getBoundingBox() is the outline shape.
        ServerLevel level = overworld(server);
        BlockPos ladderPos = new BlockPos(94, TEST_Y, 94);

        for (int dy = 1; dy <= 50; dy++) {
            level.setBlock(ladderPos.above(dy), Blocks.AIR.defaultBlockState(), 2);
        }
        // A ladder needs something to hang on; flag 2 skips the neighbour update that
        // would otherwise pop it off.
        level.setBlock(ladderPos.north(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(ladderPos,
                Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH), 2);

        BlockState placed = level.getBlockState(ladderPos);
        assertTrue(placed.is(Blocks.LADDER), "ladder did not stay placed");

        // Pin the asymmetry this test exists for, so the reason stays legible even if
        // GroundCheck is rewritten later.
        assertTrue(placed.getCollisionShape(level, ladderPos).isEmpty(),
                "a ladder is expected to have an EMPTY collision shape");
        assertFalse(placed.getShape(level, ladderPos).isEmpty(),
                "a ladder is expected to have a NON-EMPTY outline shape");

        Vec3 pos = new Vec3(ladderPos.getX() + 0.5, ladderPos.getY() + 4, ladderPos.getZ() + 0.5);
        Bot bot = BotFactory.spawn(new BotRegistry(), level, pos, 0f, 0f,
                BotGameProfiles.create("LadderBot", null), false);

        tickUntilGrounded(bot, 60);

        assertTrue(bot.isBotOnGround(),
                "bot fell through the ladder - GroundCheck is reading the collision shape "
                        + "instead of the outline shape; y=" + bot.getY());

        bot.removeBot();
    }

    @Test
    void botTakesFallDamageOnceTheGracePeriodHasElapsed(MinecraftServer server) {
        // noFallTicks starts at 60 and decrements once per tick, so a freshly spawned
        // bot is immune to fall damage for its first 60 ticks. Burn that off on solid
        // ground first, otherwise this test silently proves nothing.
        BotRegistry registry = new BotRegistry();
        BlockPos floor = new BlockPos(76, TEST_Y, 76);
        Bot bot = spawnAbove(registry, server, floor, Blocks.STONE.defaultBlockState(), 1);

        for (int i = 0; i < 70; i++) {
            bot.tick();
        }

        float healthBeforeDrop = bot.getHealth();
        bot.setPos(floor.getX() + 0.5, floor.getY() + 40, floor.getZ() + 0.5);

        for (int i = 0; i < 120 && bot.getHealth() >= healthBeforeDrop; i++) {
            bot.tick();
        }

        assertTrue(bot.getHealth() < healthBeforeDrop,
                "bot took no fall damage from 40 blocks: " + healthBeforeDrop + " -> " + bot.getHealth());

        bot.removeBot();
    }

    @Test
    void botTakesNoFallDamageLandingInWater(MinecraftServer server) {
        // isFallBlocked: water cancels fall damage. This guard was missing entirely
        // until the second review pass caught it.
        ServerLevel level = overworld(server);
        BlockPos floor = new BlockPos(88, TEST_Y, 88);

        for (int dy = 1; dy <= 50; dy++) {
            level.setBlock(floor.above(dy), Blocks.AIR.defaultBlockState(), 2);
        }
        level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(floor.above(), Blocks.WATER.defaultBlockState(), 2);

        Vec3 pos = new Vec3(floor.getX() + 0.5, floor.getY() + 2, floor.getZ() + 0.5);
        Bot bot = BotFactory.spawn(new BotRegistry(), level, pos, 0f, 0f,
                BotGameProfiles.create("WaterBot", null), false);

        for (int i = 0; i < 70; i++) {
            bot.tick();
        }

        float healthBeforeDrop = bot.getHealth();
        bot.setPos(floor.getX() + 0.5, floor.getY() + 40, floor.getZ() + 0.5);

        for (int i = 0; i < 120; i++) {
            bot.tick();
        }

        assertEquals(healthBeforeDrop, bot.getHealth(), 0.01,
                "water must cancel fall damage (isFallBlocked)");

        bot.removeBot();
    }

    // ---- identity and cleanup ---------------------------------------------

    @Test
    void botsDoNotReportThemselvesAsFakePlayers(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), false);

        assertFalse(bot.isFakePlayer(), "spec risk 4: bots must look like real players to other mods");

        bot.removeBot();
    }

    @Test
    void removalTakesTheBotOutOfTheWorld(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), false);
        int id = bot.getId();

        bot.removeBot();

        assertTrue(bot.isRemoved());
        assertNull(overworld(server).getEntity(id),
                "the entity must be gone from the level after removeBot");
    }

    @Test
    void registryEvictsRemovedBotsOnTick(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(registry, server, new Vec3(0, 250, 0), false);

        assertEquals(1, registry.size());

        bot.removeBot();
        registry.tick();

        assertEquals(0, registry.size(), "registry must drop bots removed from the world");
    }

    @Test
    void resetRemovesEveryBot(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        for (int i = 0; i < 3; i++) {
            spawn(registry, server, new Vec3(i * 4, 250, 0), false);
        }

        assertEquals(3, registry.size());

        registry.reset();

        assertEquals(0, registry.size(), "reset must clear the registry");
    }
}
