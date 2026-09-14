package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.motion.MotionVec;

import java.util.HashSet;
import java.util.Set;

/**
 * Odds and ends of bot behaviour that are neither navigation nor mining.
 *
 * <p>{@code resetHand} plus {@code miscellaneousChecks} — 146 lines of hazard handling: fire, lava
 * at three heights, magma, an MLG over water, and the boat trick that carries a bot across a lava
 * lake.
 */
public final class BotBehaviors {

    private final AgentState state;
    private final Agent agent;
    private final Mining mining;

    public BotBehaviors(AgentState state, Agent agent, Mining mining) {
        this.state = state;
        this.agent = agent;
        this.mining = mining;
    }

    /**
     * Faces the target, stops any swing animation, and empties the hand.
     *
     * <p>Ported from {@code resetHand}. Three upstream details are preserved: the {@code noFace}
     * check exists purely as an optimisation (its comment reads "LESSLAG if there is no if
     * statement here"), the early return for a bot on boat cooldown leaves the boat in its hand,
     * and {@code setItem(null)} means "restore the default item", not "empty".
     */
    public void resetHand(Bot bot, Entity target) {
        if (!state.noFace.contains(bot)) {
            bot.faceLocation(target.position());
        }

        mining.stopMining(bot);

        if (state.boatCooldown.contains(bot)) {
            return;
        }

        bot.setItem(null);
    }

    /**
     * Everything a bot does about its immediate surroundings that is not navigation.
     *
     * <p>Ported from {@code miscellaneousChecks}. The order is upstream's and is roughly
     * head-to-toe: the bot itself, then the block it is standing in, then its head, then one
     * below, then two below. Several branches overlap and upstream let them — a bot on fire
     * standing in fire calls {@code placeWaterDown} twice, and the second call returns
     * immediately because the first left water there.
     *
     * <p>The Nether is the axis every hazard branch turns on, because water cannot be placed
     * there: lava gets cobblestone instead, and fire gets punched out.
     */
    public void miscellaneousChecks(Bot bot, Entity target) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        BlockPos at = BlockPos.containing(pos);
        boolean nether = bot.isNether();

        if (bot.isBotOnFire() && !nether) {
            mining.placeWaterDown(bot, at);
        }

        Block atBlock = level.getBlockState(at).getBlock();

        if (atBlock == Blocks.FIRE || atBlock == Blocks.SOUL_FIRE) {
            if (!nether) {
                mining.placeWaterDown(bot, at);
                level.playSound(null, at, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f);
            } else {
                punchOut(bot, level, at);
            }
        }

        if (atBlock == Blocks.LAVA) {
            if (nether) {
                bot.attemptBlockPlace(at, Blocks.COBBLESTONE, false);
            } else {
                mining.placeWaterDown(bot, at);
            }
        }

        BlockPos head = at.above();
        Block headBlock = level.getBlockState(head).getBlock();

        if (headBlock == Blocks.LAVA) {
            if (nether) {
                bot.attemptBlockPlace(head, Blocks.COBBLESTONE, false);
            } else {
                mining.placeWaterDown(bot, head);
            }
        }

        if (headBlock == Blocks.FIRE || headBlock == Blocks.SOUL_FIRE) {
            if (nether) {
                punchOut(bot, level, head);
            } else {
                mining.placeWaterDown(bot, head);
            }
        }

        BlockPos under = at.below();
        Block underBlock = level.getBlockState(under).getBlock();

        // Fire underfoot is punched out in both dimensions. Upstream wrote no Nether branch
        // here, and it would be wrong to add one: putting water on the block a bot is standing
        // on drops it into the hole.
        if (underBlock == Blocks.FIRE || underBlock == Blocks.SOUL_FIRE) {
            punchOut(bot, level, under);
        }

        BlockPos under2 = at.below(2);

        // Upstream: `if (under2Type == MAGMA_BLOCK) { if (SPAWN.contains(under2Type)) { ... } }`.
        // MAGMA_BLOCK is not in SPAWN, so the inner test can never pass and the whole branch is
        // dead. Ported as dead code, deliberately. Deleting it would be right; "fixing" it by
        // dropping the inner test would make bots seal magma they currently walk over, which is
        // a behaviour change wearing a tidy-up's clothes.
        if (level.getBlockState(under2).getBlock() == Blocks.MAGMA_BLOCK
                && BlockRules.isSpawn(level.getBlockState(under2))) {
            bot.attemptBlockPlace(under2, Blocks.COBBLESTONE, true);
        }

        waterMlg(bot, target, level, pos, under, under2);

        // Lava just under the bot's feet — six tenths of a block, not a whole one, so this fires
        // while the bot is still falling into it rather than after it has landed.
        if (level.getBlockState(BlockPos.containing(pos.add(0, -0.6, 0))).getBlock() == Blocks.LAVA) {
            boatOverLava(bot, target, level, pos);
        }
    }

    /** Look down, swing, and clear the block. Upstream's inline fire-punch, written three times. */
    private void punchOut(Bot bot, ServerLevel level, BlockPos pos) {
        bot.look(Direction.DOWN);
        bot.punch();
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    /**
     * Cobblestone into the water a slowly-falling bot is about to land in.
     *
     * <p>Upstream's four nested {@code if}s, inverted into guard clauses. Not the fall-damage
     * MLG — that is {@code LegacyAgent.onFallDamage} — but nearly its opposite: a bot that is
     * <b>not</b> falling fast enough to be hurt, filling in the water under itself so it has
     * something to stand on. The {@code miningAnim} test is what stops it doing this while it is
     * busy breaking a block.
     */
    private void waterMlg(Bot bot, Entity target, ServerLevel level, Vec3 pos,
                          BlockPos under, BlockPos under2) {
        if (BotMath.floorY(pos) > BotMath.floorY(target.position()) + 1
                || state.miningAnim.containsKey(bot)
                || bot.getVelocity().getY() < -0.6) {
            return;
        }

        if (level.getBlockState(BlockPos.containing(pos.add(0, -0.6, 0))).getBlock() != Blocks.WATER
                || BlockRules.isNoCrack(level.getBlockState(under2))
                || !level.getBlockState(BlockPos.containing(bot.getEyePosition())).isAir()) {
            return;
        }

        if (BlockRules.isWater(level.getBlockState(under))) {
            bot.attemptBlockPlace(under, Blocks.COBBLESTONE, true);
        }
    }

    /**
     * Spawns a boat under the bot and shoves it toward the target.
     *
     * <p>Ported from the last block of {@code miscellaneousChecks}. A boat floats on lava, so
     * this is how a bot crosses a lava lake. The boat is removed after 20 ticks whether or not it
     * worked, and the bot is on a 5-tick cooldown so it cannot spam them.
     *
     * <p>26.2 split boats per wood and moved the constants to {@code EntityTypes}; oak matches
     * the {@code OAK_BOAT} item upstream put in the bot's hand.
     */
    private void boatOverLava(Bot bot, Entity target, ServerLevel level, Vec3 pos) {
        if (state.boatCooldown.contains(bot)) {
            return;
        }

        state.boatCooldown.add(bot);

        Vec3 place = pos.add(0, -0.1, 0);

        bot.setItem(new ItemStack(Items.OAK_BOAT));
        bot.look(Direction.DOWN);
        bot.punch();

        Boat boat = EntityTypes.OAK_BOAT.create(level, EntitySpawnReason.COMMAND);

        if (boat == null) {
            // Upstream's spawnEntity could not return null and had no branch for it. Releasing
            // the cooldown is the only sensible reading: a bot that failed to get a boat should
            // be free to try again rather than sit in lava for five ticks.
            state.boatCooldown.remove(bot);
            return;
        }

        boat.snapTo(place.x, place.y, place.z, bot.getYRot(), 0f);
        level.addFreshEntity(boat);
        state.boats.add(boat);

        agent.later(20, () -> {
            if (boat.isAlive()) {
                state.boats.remove(boat);
                boat.discard();
            }
        });

        agent.later(1, () -> bot.look(Direction.DOWN));

        bot.stand();

        MotionVec vector = MotionVec.of(target.position().subtract(bot.position())).normalize();
        vector.multiply(0.8);

        MotionVec move = bot.getVelocity().add(vector).setY(0);

        if (move.length() > 1) {
            move.normalize();
        }

        move.multiply(0.5).setY(0.42);
        bot.setVelocity(move);

        agent.later(5, () -> {
            state.boatCooldown.remove(bot);

            if (bot.isBotAlive()) {
                bot.faceLocation(target.position());
            }
        });
    }

    /**
     * Whether the bot is sitting on one of the boats this agent spawned.
     *
     * <p>Ported from {@code onBoat}. {@code tickBot} treats a bot on a boat as grounded, so it
     * can navigate and attack while floating. The dead-boat sweep is upstream's, including its
     * limit: the set is pruned only when someone asks, and only of the boats walked past before
     * a match was found.
     */
    public boolean onBoat(Bot bot) {
        Set<Boat> dead = new HashSet<>();
        boolean found = false;

        for (Boat boat : state.boats) {
            if (bot.level() != boat.level()) {
                continue;
            }

            if (!boat.isAlive()) {
                dead.add(boat);
                continue;
            }

            if (bot.position().distanceTo(boat.position()) < 1) {
                found = true;
                break;
            }
        }

        state.boats.removeAll(dead);
        return found;
    }
}
