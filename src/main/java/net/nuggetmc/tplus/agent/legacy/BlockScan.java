package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.util.BotUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Placing blocks: towering, and the two clutch routines.
 *
 * <p>Ported from {@code LegacyBlockCheck}, which was a real collaborator of {@code LegacyAgent}
 * constructed with {@code (LegacyAgent, Plugin)} and held 8 of the 27 {@code runTaskLater} sites.
 *
 * <p>{@code placeBlock} is the tower step; {@code tryPreMLG} and {@code clutch} are the two
 * placements that make these bots hard to kill.
 */
public final class BlockScan {

    private final AgentState state;
    private final Agent agent;

    public BlockScan(AgentState state, Agent agent) {
        this.state = state;
        this.agent = agent;
    }

    /**
     * Places cobblestone at {@code pos}, shoring up the block below it first when that block is
     * empty too.
     *
     * <p>Ported from {@code placeBlock}. The neighbour tests are upstream's, in upstream's order,
     * and they ask one question in four increasingly desperate ways: <i>is there anything solid
     * nearby for this block to look like it is attached to?</i> A bot towering in mid-air needs a
     * block under the one it stands on, or the placement looks wrong to clients.
     *
     * <p>The delays — 1, 2 and 3 ticks — are upstream's, and are what make the two-block
     * placements look like a player doing it rather than a block appearing.
     *
     * <p>Note that the first branch does <b>not</b> return, which is upstream's and looks like an
     * oversight: a bot with nothing under it both starts the two-block sequence and then falls
     * through into the neighbour tests below, so {@code pos} can be filled immediately and filled
     * again two ticks later. {@code placeFinal} is idempotent, so the only cost is a second sound.
     */
    public void placeBlock(Bot bot, BlockPos pos) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos under = pos.below();

        if (BlockRules.isSpawn(level.getBlockState(under))) {
            placeFinal(bot, under);
            agent.later(2, () -> placeFinal(bot, pos));
        }

        // Any solid neighbour at this level is enough.
        for (BlockPos side : sides(pos)) {
            if (!BlockRules.isSpawn(level.getBlockState(side))) {
                placeFinal(bot, pos);
                return;
            }
        }

        // Failing that, any solid neighbour one level down, plus room beneath for the support.
        boolean edge = false;

        for (BlockPos side : sides(under)) {
            if (!BlockRules.isSpawn(level.getBlockState(side))) {
                edge = true;
            }
        }

        if (edge && BlockRules.isSpawn(level.getBlockState(under))) {
            placeFinal(bot, under);
            agent.later(2, () -> placeFinal(bot, pos));
            return;
        }

        // Failing that, a diagonal, which needs a bridge block placed toward it first.
        boolean bridged = false;

        if (!BlockRules.isSpawn(level.getBlockState(under.offset(1, 0, 1)))
                || !BlockRules.isSpawn(level.getBlockState(under.offset(1, 0, -1)))) {
            BlockPos bridge = under.east();

            if (BlockRules.isSpawn(level.getBlockState(bridge))) {
                placeFinal(bot, bridge);
            }

            bridged = true;
        } else if (!BlockRules.isSpawn(level.getBlockState(under.offset(-1, 0, 1)))
                || !BlockRules.isSpawn(level.getBlockState(under.offset(-1, 0, -1)))) {
            BlockPos bridge = under.west();

            if (BlockRules.isSpawn(level.getBlockState(bridge))) {
                placeFinal(bot, bridge);
            }

            bridged = true;
        }

        if (bridged) {
            agent.later(1, () -> {
                if (BlockRules.isSpawn(level.getBlockState(under))) {
                    placeSound(level, pos);
                    placeFinal(bot, under);
                }
            });

            agent.later(3, () -> {
                placeSound(level, pos);
                placeFinal(bot, pos);
            });
            return;
        }

        placeSound(level, pos);
        placeFinal(bot, pos);
    }

    /**
     * Puts cobblestone at {@code pos}, and turns lava directly below it into cobblestone too.
     *
     * <p>Ported from {@code placeFinal}. The lava rule is upstream's: a bot towering out of a
     * lava lake seals the surface under itself as it goes.
     */
    private void placeFinal(Bot bot, BlockPos pos) {
        ServerLevel level = (ServerLevel) bot.level();

        if (level.getBlockState(pos).getBlock() == Blocks.COBBLESTONE) {
            return;
        }

        placeSound(level, pos);
        bot.setItem(new ItemStack(Items.COBBLESTONE));
        level.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());

        BlockPos under = pos.below();

        if (level.getBlockState(under).getBlock() == Blocks.LAVA) {
            level.setBlockAndUpdate(under, Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    private static void placeSound(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1f, 1f);
    }

    /**
     * Places cobblestone under a falling bot, before it needs a water bucket.
     *
     * <p>Ported from {@code tryPreMLG}. Three gates before anything happens: the bot must be
     * airborne, falling faster than -0.8, and have fewer than eight no-fall ticks left. Then it
     * tries three blocks down, and failing that two.
     *
     * <p>Upstream's return value is {@code false} on every path — including the one that places
     * a block — and its only caller ignored it. Void here; nothing ever read it.
     */
    public void tryPreMLG(Bot bot, Vec3 pos) {
        if (bot.isBotOnGround() || bot.getVelocity().getY() >= -0.8 || bot.getNoFallTicks() > 7) {
            return;
        }

        if (tryPreMLG(bot, pos, 3)) {
            return;
        }

        tryPreMLG(bot, pos, 2);
    }

    /**
     * Looks {@code blocksBelow} down from each corner of the bot's footprint.
     *
     * <p>The rule, in upstream's words rearranged: every block between the bot and the candidate
     * must be pass-through, and the candidate must be something the bot would land on but
     * <b>cannot</b> place water or vines on — because if it could, {@code onFallDamage} will
     * handle it later and a cobblestone block now would be wasted.
     *
     * @return whether a block was placed. Always false, as upstream's did; the caller ignores it
     *         and the {@code tryPreMLG(bot, pos, 3)} attempt above therefore always falls through
     *         to the two-block one. Faithfully pointless.
     */
    private boolean tryPreMLG(Bot bot, Vec3 pos, int blocksBelow) {
        ServerLevel level = (ServerLevel) bot.level();
        AABB box = bot.getBotBoundingBox();
        boolean nether = bot.isNether();

        double[] xs = {box.minX, box.maxX - 0.01};
        double[] zs = {box.minZ, box.maxZ - 0.01};

        // LinkedHashSet where upstream had a HashSet: the sort below has ties, and an unordered
        // set makes which of two equally good candidates wins depend on hash order.
        Set<BlockPos> candidates = new LinkedHashSet<>();

        for (double x : xs) {
            for (double z : zs) {
                int baseY = BotMath.floorY(pos);
                int floorX = (int) Math.floor(x);
                int floorZ = (int) Math.floor(z);

                // Everything on the way down must be pass-through. Upstream returns from the
                // whole method on the first blocked corner rather than skipping that corner.
                for (int i = 1; i < blocksBelow; i++) {
                    BlockState between = level.getBlockState(new BlockPos(floorX, baseY - i, floorZ));

                    if (BlockRules.isSolid(between) || BlockRules.canStandOn(between)) {
                        return false;
                    }
                }

                candidates.add(new BlockPos(floorX, baseY - blocksBelow, floorZ));
            }
        }

        // Keep only candidates that are landable AND unplaceable.
        candidates.removeIf(candidate -> {
            boolean placeable = nether
                    ? BlockPlacement.canPlaceTwistingVines(level, candidate)
                    : BlockPlacement.canPlaceWater(level, candidate, OptionalDouble.empty());

            BlockState state = level.getBlockState(candidate);
            return placeable || (!BlockRules.isSolid(state) && !BlockRules.canStandOn(state));
        });

        if (candidates.isEmpty()) {
            return false;
        }

        List<BlockPos> sorted = new ArrayList<>(candidates);

        // Clear air above first, then nearest horizontally. Upstream's second arm reads
        // `if (!bAir && aAir) return 1`, which is its FIRST arm's condition rather than the
        // mirror of it -- so it is unreachable, and the comparator is not antisymmetric.
        // List.sort is entitled to throw for that. This writes the mirror the comment intended;
        // it is a deviation, and it is in the register.
        sorted.sort((a, b) -> {
            boolean aClear = level.getBlockState(a.above()).isAir();
            boolean bClear = level.getBlockState(b.above()).isAir();

            if (aClear && !bClear) {
                return -1;
            }

            if (bClear && !aClear) {
                return 1;
            }

            return Double.compare(BotUtils.getHorizSqDist(a, pos), BotUtils.getHorizSqDist(b, pos));
        });

        BlockPos faceTarget = sorted.get(0);
        BlockPos place = faceTarget.above();

        // The lower corner, as upstream's block Location was. Aiming at the centre instead
        // tilts the head by up to half a block in each axis, which is visible on a bot that is
        // falling past it.
        Vec3 face = Vec3.atLowerCornerOf(faceTarget);

        bot.faceLocation(face);
        bot.look(Direction.DOWN);
        agent.later(1, () -> bot.faceLocation(face));

        bot.punch();
        placeSound(level, place);
        bot.setItem(new ItemStack(Items.COBBLESTONE));
        level.setBlockAndUpdate(place, Blocks.COBBLESTONE.defaultBlockState());

        return false;
    }

    /**
     * Seals a two-block drop under a bot whose target is above it.
     *
     * <p>Ported from {@code clutch}. Both blocks below must be replaceable, and at least one
     * horizontal neighbour of the block directly below must be solid — the block is placed
     * against something rather than in mid-air.
     *
     * <p>The bot goes into {@code slow} for 12 ticks and {@code noFace} for 15, which is what
     * stops it turning away mid-placement and walking off its own block. These two windows are
     * the only writers of either set.
     */
    public void clutch(Bot bot, LivingEntity target) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos botPos = BlockPos.containing(bot.position());

        if (!BlockRules.isSpawn(level.getBlockState(botPos.below()))
                || !BlockRules.isSpawn(level.getBlockState(botPos.below(2)))) {
            return;
        }

        if (BotMath.floorY(target.position()) < botPos.getY()) {
            return;
        }

        BlockPos place = botPos.below();
        BlockPos anchor = null;

        // Upstream kept the LAST matching neighbour, not the first -- there is no break. Its
        // neighbours came out of a HashSet, so which one that was is unspecified; sides() fixes
        // the order, and the last of it is north.
        for (BlockPos side : sides(place)) {
            if (!BlockRules.isSpawn(level.getBlockState(side))) {
                anchor = side;
            }
        }

        if (anchor == null) {
            return;
        }

        state.slow.add(bot);
        state.noFace.add(bot);

        agent.later(12, () -> {
            bot.stand();
            state.slow.remove(bot);
        });

        agent.later(15, () -> state.noFace.remove(bot));

        Vec3 faceTarget = Vec3.atLowerCornerOf(anchor).add(0, -1.5, 0);

        bot.faceLocation(faceTarget);
        bot.look(Direction.DOWN);
        agent.later(1, () -> bot.faceLocation(faceTarget));

        bot.punch();
        bot.sneak();
        placeSound(level, place);
        bot.setItem(new ItemStack(Items.COBBLESTONE));
        level.setBlockAndUpdate(place, Blocks.COBBLESTONE.defaultBlockState());
    }

    /**
     * The four horizontal neighbours.
     *
     * <p>Upstream built these as a {@code HashSet} and iterated it, so the order was unspecified.
     * A list fixes one. That would be a real difference if the loop body did anything other than
     * set a boolean — the first loop below even returns early where upstream kept iterating —
     * so a side effect added to either loop has to bring back the full pass.
     */
    private static List<BlockPos> sides(BlockPos pos) {
        return List.of(pos.east(), pos.west(), pos.south(), pos.north());
    }
}
