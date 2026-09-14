package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.util.BotUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a bot should break to reach its target, and where.
 *
 * <p>Ported from {@code LegacyAgent.checkNearby}, 280 lines, alone in this file by spec §4.3. It
 * has a side effect: whatever it decides on, it has already called {@code preBreak} for by the
 * time it returns.
 *
 * <p>Upstream's four directional arms were byte-identical copies — proven, not assumed; see the
 * commit that added this file — and are one parameterised body here. The per-direction tests are
 * the standing proof that the deduplication stays faithful.
 */
public final class SurroundingScan {

    private final AgentState state;
    private final Agent agent;
    private final Mining mining;

    public SurroundingScan(AgentState state, Agent agent, Mining mining) {
        this.state = state;
        this.agent = agent;
        this.mining = mining;
    }

    /**
     * Finds the block standing between {@code bot} and {@code target} and starts breaking it.
     *
     * @return the offset naming what is being broken, or null when nothing is in the way
     */
    public @Nullable ScanOffset checkNearby(Bot bot, LivingEntity target) {
        ServerLevel level = (ServerLevel) bot.level();

        // Note the order: the bot turns to face its target first, and every "direction" below is
        // read back off the bot afterwards. The scan is therefore always along the line to the
        // target, never along whatever way the bot happened to be looking.
        bot.faceLocation(target.position());

        Direction dir = bot.getDirection();

        // Phase one: the bot's own footprint. It runs before the directional scan, so a fence
        // beside the bot beats a wall in front of it.
        ScanOffset footprint = scanFootprint(bot, level, dir);

        if (footprint != null) {
            return footprint;
        }

        if (dir.getAxis().isVertical()) {
            return null;
        }

        // Phase two: one step in the facing direction, at four heights.
        Scan scan = scanAhead(bot, level, dir);

        if (scan == null) {
            return null;
        }

        // Late rejections: a knee-height block with clear air above it is a step, not a wall.
        if (isWalkableStep(bot, level, scan) || isDuckable(bot, level, dir, scan)) {
            return null;
        }

        if (scan.offset() == ScanOffset.BELOW) {
            state.noJump.add(bot);
            agent.later(15, () -> state.noJump.remove(bot));

            bot.look(Direction.DOWN);
            mining.downMine(bot, scan.pos());
        } else if (scan.offset() == ScanOffset.ABOVE) {
            bot.look(Direction.UP);
        }

        mining.preBreak(bot, scan.pos(), scan.offset());
        return scan.offset();
    }

    /** A chosen block and the offset naming it. */
    private record Scan(BlockPos pos, ScanOffset offset) {
    }

    /**
     * Scans the four corners of the bot's own bounding box for a fence, nearest first.
     *
     * <p>Upstream's first loop. The corners come from the bounding box with {@code maxX - 0.01}
     * and {@code maxZ - 0.01} — the same trick {@code Bot.isFallBlocked} uses, so a box ending
     * exactly on a boundary does not sample the next block along.
     *
     * <p>A fence only counts when it is one block away on exactly one axis <b>and</b> the bot is
     * facing across that axis: a bot facing north reports a fence to its east or west, never one
     * directly ahead. That is upstream's rule, and it is why this does not simply duplicate the
     * directional scan.
     *
     * <p>Reachability, because it is easy to read this as "the bot's neighbours": it is not. The
     * corners come from a 0.6-wide box, so a bot centred in its block yields four candidates all
     * inside that one block and {@link #footprintOffset} can never match. This fires only for a
     * bot straddling a block boundary — which, mid-jump between two blocks, is most of the time.
     */
    private @Nullable ScanOffset scanFootprint(Bot bot, ServerLevel level, Direction dir) {
        AABB box = bot.getBotBoundingBox();
        Vec3 pos = bot.position();

        double[] xs = {box.minX, box.maxX - 0.01};
        double[] zs = {box.minZ, box.maxZ - 0.01};

        List<BlockPos> footprint = new ArrayList<>();

        for (double x : xs) {
            for (double z : zs) {
                BlockPos candidate = new BlockPos((int) Math.floor(x), BotMath.floorY(pos),
                        (int) Math.floor(z));

                if (!footprint.contains(candidate)) {
                    footprint.add(candidate);
                }
            }
        }

        footprint.sort((a, b) -> Double.compare(
                BotUtils.getHorizSqDist(a, pos), BotUtils.getHorizSqDist(b, pos)));

        for (BlockPos candidate : footprint) {
            boolean up = false;
            BlockPos found = candidate;

            if (!BlockRules.isFence(level.getBlockState(found))) {
                up = true;
                found = candidate.above();

                if (!BlockRules.isFence(level.getBlockState(found))) {
                    continue;
                }
            }

            ScanOffset offset = footprintOffset(bot, dir, found, up);

            if (offset != null) {
                // The block's lower corner, which is what upstream's get.getLocation() was.
                bot.faceLocation(Vec3.atLowerCornerOf(found));
                mining.preBreak(bot, found, offset);
                return offset;
            }
        }

        return null;
    }

    /**
     * The offset for a footprint fence, or null when the geometry does not qualify.
     *
     * <p>Upstream's four-way if-chain. A fence at +X with the bot facing north or south is EAST
     * when it was found at head height and EAST_D when found at foot level, and so on for the
     * other three.
     */
    private static @Nullable ScanOffset footprintOffset(Bot bot, Direction dir,
                                                        BlockPos found, boolean up) {
        BlockPos botPos = BlockPos.containing(bot.position());

        int dx = found.getX() - botPos.getX();
        int dz = found.getZ() - botPos.getZ();

        boolean facingZ = dir == Direction.NORTH || dir == Direction.SOUTH;
        boolean facingX = dir == Direction.EAST || dir == Direction.WEST;

        if (dx == 1 && dz == 0 && facingZ) {
            return up ? ScanOffset.EAST : ScanOffset.EAST_D;
        }

        if (dx == -1 && dz == 0 && facingZ) {
            return up ? ScanOffset.WEST : ScanOffset.WEST_D;
        }

        if (dx == 0 && dz == 1 && facingX) {
            return up ? ScanOffset.SOUTH : ScanOffset.SOUTH_D;
        }

        if (dx == 0 && dz == -1 && facingX) {
            return up ? ScanOffset.NORTH : ScanOffset.NORTH_D;
        }

        return null;
    }

    /**
     * One step in the facing direction, checked at four heights in upstream's order.
     *
     * <p>This is the parameterised form of upstream's four identical switch arms. Read against
     * any one of them, {@code sideAt} is {@code NORTH}, {@code sideDown} is {@code NORTH_D},
     * {@code sideDown2} is {@code NORTH_D_2} and the {@code _U} in {@link #scanFooting} is
     * {@code NORTH_U} — all derived through {@link ScanOffset#sideUp()} and
     * {@link ScanOffset#sideDown()} rather than named, which is what lets one body cover four
     * directions.
     *
     * <p>The first two heights ask {@code blocksPath} and the third asks {@code isFence}. They
     * are different questions: upstream called {@code checkSideBreak}, which is the negation of
     * BREAK membership, for the first two and tested FENCE membership directly for the third.
     * Swapping them makes a bot mine air.
     */
    private @Nullable Scan scanAhead(Bot bot, ServerLevel level, Direction dir) {
        BlockPos botPos = BlockPos.containing(bot.position());
        BlockPos ahead = botPos.above().relative(dir);

        ScanOffset sideAt = sideAtFor(dir);

        // Head height first.
        if (BlockRules.blocksPath(level.getBlockState(ahead))) {
            return new Scan(ahead, sideAt);
        }

        // Then knee height.
        BlockPos kneeAhead = ahead.below();

        if (BlockRules.blocksPath(level.getBlockState(kneeAhead))) {
            return new Scan(kneeAhead, sideAt.sideDown());
        }

        // Then a fence two down. A fence is short, so it only obstructs from below.
        BlockPos lowAhead = ahead.below(2);

        if (BlockRules.isFence(level.getBlockState(lowAhead))) {
            return new Scan(lowAhead, sideAt.sideDown().sideDown());
        }

        // Nothing ahead. The bot may still be wedged: check what it is standing on.
        return scanFooting(bot, level, ahead, sideAt);
    }

    /**
     * When nothing ahead is in the way, whether the bot is wedged on its own footing.
     *
     * <p>Upstream's {@code else} branch. Three outcomes, in order: break the block the bot is
     * standing on (BELOW), break the block over its head (ABOVE), or break the block over the
     * space ahead (the {@code _U} offset).
     */
    private @Nullable Scan scanFooting(Bot bot, ServerLevel level, BlockPos ahead,
                                       ScanOffset sideAt) {
        List<BlockPos> standing = bot.getStandingOn();

        if (standing.isEmpty()) {
            return null;
        }

        BlockPos footing = standing.get(0);
        BlockPos botPos = BlockPos.containing(bot.position());
        BlockState footingState = level.getBlockState(footing);

        // "Obstructed" means the footing is level with the bot's own block, or one below it and
        // a fence or gate — a bot standing on a fence post is wedged.
        boolean obstructed = footing.getY() == botPos.getY()
                || (footing.getY() + 1 == botPos.getY()
                        && (BlockRules.isFence(footingState) || BlockRules.isGate(footingState)));

        if (!obstructed) {
            return null;
        }

        BlockState below = level.getBlockState(footing.below());

        if (!BlockRules.isBreak(below) && !BlockRules.isNonSolid(below)) {
            return new Scan(footing, ScanOffset.BELOW);
        }

        BlockPos overhead = botPos.above(2);

        if (!BlockRules.isBreak(level.getBlockState(overhead))) {
            return new Scan(overhead, ScanOffset.ABOVE);
        }

        BlockPos overheadAhead = ahead.above();

        if (!BlockRules.isBreak(level.getBlockState(overheadAhead))) {
            return new Scan(overheadAhead, sideAt.sideUp());
        }

        return null;
    }

    /**
     * Whether a knee- or ankle-height block is a step the bot can simply walk up.
     *
     * <p>Upstream's late rejection for the {@code _D} and {@code _D_2} offsets: with clear air
     * two above both the bot and the block, and the block not a fence or gate, it is a step.
     * Without this a bot mines every staircase it meets.
     *
     * <p><b>The first term is shared with {@code Navigation.canJumpHere}</b>, which decides
     * whether a bot jumps or walks. Both ask {@code isAir} at {@code botPos.above(2)}, and the
     * pairing is deliberate: under a ceiling this returns false, so a step is mined rather than
     * hopped, and a walking bot is therefore never asked to climb something it cannot. Respell
     * one of the two and bots jam against knee-high blocks inside their own tunnels.
     */
    private boolean isWalkableStep(Bot bot, ServerLevel level, Scan scan) {
        ScanOffset offset = scan.offset();

        if (!offset.isSideDown() && !offset.isSideDown2()) {
            return false;
        }

        BlockPos botPos = BlockPos.containing(bot.position());
        BlockState blockState = level.getBlockState(scan.pos());

        return BlockRules.isAir(level.getBlockState(botPos.above(2)))
                && BlockRules.isAir(level.getBlockState(scan.pos().above(2)))
                && !BlockRules.isFence(blockState)
                && !BlockRules.isGate(blockState);
    }

    /**
     * Whether an ABOVE or BELOW result can be walked out of rather than mined.
     *
     * <p>Upstream's second late rejection: with air over the bot's head and air over the space
     * ahead at the same height, there is a gap to move through.
     */
    private boolean isDuckable(Bot bot, ServerLevel level, Direction dir, Scan scan) {
        if (scan.offset() != ScanOffset.ABOVE && scan.offset() != ScanOffset.BELOW) {
            return false;
        }

        BlockPos botPos = BlockPos.containing(bot.position());
        BlockPos check = botPos.above(2).relative(dir);

        return BlockRules.isAir(level.getBlockState(botPos.above(2)))
                && BlockRules.isAir(level.getBlockState(check));
    }

    /** The at-head-height side offset for a horizontal direction. */
    private static ScanOffset sideAtFor(Direction dir) {
        return switch (dir) {
            case NORTH -> ScanOffset.NORTH;
            case SOUTH -> ScanOffset.SOUTH;
            case EAST -> ScanOffset.EAST;
            case WEST -> ScanOffset.WEST;
            default -> throw new IllegalArgumentException("horizontal only: " + dir);
        };
    }
}
