package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.event.BotDeathEvent;
import net.nuggetmc.tplus.event.BotFallDamageEvent;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.util.PlayerUtils;

import java.util.OptionalDouble;

/**
 * The bot AI. Ported from {@code api/agent/legacyagent/LegacyAgent}, whose own header comment
 * reads "Yes, this code is very unoptimized, I know."
 *
 * <p>Split per spec §4.3 into this plus {@code Targeting}, {@code Navigation},
 * {@code SurroundingScan}, {@code Mining}, {@code BotBehaviors} and {@code BlockScan}. This class
 * keeps the tick flow, the three event handlers and the small helpers, and holds no state of its
 * own beyond the shared {@link AgentState}.
 *
 * <p>The ordering inside {@link #tickBot(Bot)} is the specification for the rest of the port.
 * Every terrain check returns "handled, stop here", so moving one changes behaviour.
 */
public final class LegacyAgent extends Agent {

    private final AgentState state;
    private final Targeting targeting;
    private final Navigation navigation;
    private final BotBehaviors behaviors;
    private final Mining mining;

    /** Whether bots aim at a ring around the target rather than the target itself. */
    public boolean offsets = true;

    public LegacyAgent(BotRegistry registry) {
        super(registry);

        this.state = registry.state();
        this.targeting = new Targeting(registry);
        this.mining = new Mining(state, this);
        this.navigation = new Navigation(state, this);
        this.behaviors = new BotBehaviors(state, navigation);
    }

    public Targeting targeting() {
        return targeting;
    }

    @Override
    public void tick() {
        targeting.beginTick();
    }

    @Override
    public void tickBot(Bot bot) {
        if (!bot.isBotAlive()) {
            return;
        }

        if (bot.tickDelay(20)) {
            center(bot);
        }

        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        LivingEntity livingTarget = targeting.locateTarget(bot, pos);

        // Task 23: blockScan.tryPreMLG(bot, pos);

        if (livingTarget == null) {
            // Task 19: mining.stopMining(bot);
            return;
        }

        // Task 23: blockScan.clutch(bot, livingTarget);

        fallDamageCheck(bot);
        // Task 22: behaviors.miscellaneousChecks(bot, livingTarget);

        Vec3 target = offsets
                ? livingTarget.position().add(bot.getOffset().toVec3())
                : livingTarget.position();

        if (bot.tickDelay(3) && !state.miningAnim.containsKey(bot)) {
            Vec3 botEye = bot.getEyePosition();
            Vec3 targetEye = livingTarget.getEyePosition();
            Vec3 targetPos = livingTarget.position();

            // Two rays, not one: eye-to-eye or eye-to-feet. A bot behind a half-height wall
            // can still reach over it.
            if (LegacyUtils.checkFreeSpace(level, botEye, targetEye)
                    || LegacyUtils.checkFreeSpace(level, botEye, targetPos)) {
                attack(bot, livingTarget, pos);
            }
        }

        boolean waterGround = BlockRules.isWater(stateAt(level, pos.add(0, -0.1, 0)))
                && !BlockRules.isAir(stateAt(level, pos.add(0, -0.6, 0)));

        boolean withinTargetXZ = false;
        boolean sameXZ = Boolean.TRUE.equals(state.btCheck.get(bot));

        // Task 22 adds `|| behaviors.onBoat(bot)` to this condition.
        if (waterGround || bot.isBotOnGround()) {
            byte sideResult = 1;

            // Task 22: towerList reset when the bot has climbed above its target.

            if (Math.abs(BotMath.floorX(pos) - BotMath.floorX(target)) <= 3
                    && Math.abs(BotMath.floorZ(pos) - BotMath.floorZ(target)) <= 3) {
                withinTargetXZ = true;
            }

            // Declared here to match upstream's order, and deliberately not read until task 19
            // adds checkDown. It is here so the three later insertions do not have to reorder
            // this block.
            boolean bothXZ = withinTargetXZ || sameXZ;

            // Upstream captures the head block before the XZ comparison and uses it for
            // checkAt; the other two read the block the bot is standing in. The order matters:
            // a bot inside a fence with something solid above it mines upward first.
            BlockPos botPos = BlockPos.containing(pos);
            BlockPos headPos = botPos.above();

            if (BlockRules.blocksPath(level.getBlockState(headPos))) {
                mining.preBreak(bot, headPos, ScanOffset.AT);
                return;
            }

            if (BlockRules.isFenceOrGate(level.getBlockState(botPos))) {
                mining.preBreak(bot, botPos, ScanOffset.AT_D);
                return;
            }

            if (BlockRules.isObstacleOrDoor(level.getBlockState(botPos))) {
                mining.preBreak(bot, botPos, ScanOffset.AT_D);
                return;
            }

            // Tasks 19 and 21 insert their checks here, in this order:
            //   checkDown, checkUp (only when withinTargetXZ || sameXZ),
            //   checkSide (only when bothXZ).
            // Each returns true for "handled", and tickBot returns immediately on true.

            switch (sideResult) {
                case 1:
                    behaviors.resetHand(bot, livingTarget);
                    if (!state.noJump.contains(bot) && !waterGround) {
                        navigation.move(bot, livingTarget, pos, target);
                    }
                    return;

                case 2:
                    if (!waterGround) {
                        navigation.move(bot, livingTarget, pos, target);
                    }
            }
        } else if (BlockRules.isWater(stateAt(level, pos))) {
            navigation.swim(bot, target, livingTarget,
                    BlockRules.isWater(stateAt(level, pos.add(0, -1, 0))));
        }
    }

    /**
     * Records whether the bot has stayed in the same block column since the last sample.
     *
     * <p>Ported from {@code center}, called every 20 ticks. {@code sameXZ} is the signal that a
     * bot is stuck, and it is what unlocks {@code checkUp} and {@code checkSide} — a bot that is
     * going nowhere starts mining.
     */
    private void center(Bot bot) {
        if (!bot.isBotAlive()) {
            return;
        }

        Vec3 prev = state.btList.get(bot);
        Vec3 pos = bot.position();

        if (prev != null) {
            state.btCheck.put(bot, BotMath.floorX(pos) == BotMath.floorX(prev)
                    && BotMath.floorZ(pos) == BotMath.floorZ(prev));
        }

        state.btList.put(bot, pos);
    }

    /**
     * Puts the clutch item in the bot's hand while it is falling.
     *
     * <p>Ported from {@code LegacyAgent.fallDamageCheck} — distinct from {@code Bot}'s method of
     * the same name, which is the one that actually applies damage. This only prepares: it looks
     * down and equips a water bucket, or twisting vines in the Nether.
     */
    private void fallDamageCheck(Bot bot) {
        if (!bot.isFalling()) {
            return;
        }

        bot.look(Direction.DOWN);
        bot.setItem(new ItemStack(bot.isNether() ? Items.TWISTING_VINES : Items.WATER_BUCKET));
    }

    /**
     * Hits the target if it is hittable.
     *
     * <p>Ported from {@code attack}. Three gates, all upstream's: an invincible player is
     * skipped, a target still inside its damage immunity window is skipped, and anything 4
     * blocks or further away is skipped. {@code invulnerableTime} is the vanilla field behind
     * Bukkit's {@code getNoDamageTicks}.
     */
    private void attack(Bot bot, LivingEntity target, Vec3 pos) {
        boolean invincible = target instanceof ServerPlayer player
                && PlayerUtils.isInvincible(player.gameMode());

        if (invincible || target.invulnerableTime >= 5 || pos.distanceTo(target.position()) >= 4) {
            return;
        }

        bot.attack(target);
    }

    @Override
    public void onBotDeath(BotDeathEvent event) {
        if (!drops) {
            event.getDrops().clear();
        }
    }

    /**
     * Cancels a hit that a raised shield should have stopped.
     *
     * <p>Ported from {@code onPlayerDamage}. The dot product is the check: it compares the
     * direction from the attacker to the bot against the way the bot is looking, so a shield
     * only works against attacks from roughly the front. {@code -0.1} is upstream's threshold
     * and is slightly generous — a hit from just past 90 degrees still blocks.
     */
    @Override
    public void onPlayerDamage(BotDamageByPlayerEvent event) {
        Bot bot = event.getBot();
        ServerPlayer player = event.getPlayer();

        Vec3 toBot = bot.position().subtract(player.position());

        if (toBot.lengthSqr() == 0) {
            return;
        }

        double dot = toBot.normalize().dot(bot.getLookAngle());

        if (bot.isBotBlocking() && dot >= -0.1) {
            ServerLevel level = (ServerLevel) bot.level();
            level.playSound(null, bot.blockPosition(), SoundEvents.SHIELD_BLOCK.value(),
                    SoundSource.PLAYERS, 1f, 1f);
            event.setCancelled(true);
        }
    }

    /**
     * The MLG. Ported from {@code onFallDamage}.
     *
     * <p>Finds somewhere in {@code standingOn} that will take water (or twisting vines in the
     * Nether), places it, cancels the fall damage, and schedules picking the water back up five
     * ticks later. Cancelling is what "the clutch worked" means; if nothing takes the placement
     * the event is left alone and the bot takes the hit.
     *
     * <p>The waterlogging branch is upstream's: placing water "on" a waterloggable block means
     * setting its {@code waterlogged} property rather than replacing it, and the pickup has to
     * undo it the same way.
     */
    @Override
    public void onFallDamage(BotFallDamageEvent event) {
        Bot bot = event.getBot();
        ServerLevel level = (ServerLevel) bot.level();
        boolean nether = bot.isNether();
        double yPos = bot.getY();

        bot.look(Direction.DOWN);

        Item itemType = nether ? Items.TWISTING_VINES : Items.WATER_BUCKET;
        Block placeType = nether ? Blocks.TWISTING_VINES : Blocks.WATER;
        SoundEvent sound = nether ? SoundEvents.WEEPING_VINES_PLACE : SoundEvents.BUCKET_EMPTY;

        BlockPos ground = null;

        for (BlockPos candidate : event.getStandingOn()) {
            boolean ok = nether
                    ? BlockPlacement.canPlaceTwistingVines(level, candidate)
                    : BlockPlacement.canPlaceWater(level, candidate, OptionalDouble.of(yPos));

            if (ok) {
                ground = candidate;
                break;
            }
        }

        if (ground == null) {
            return;
        }

        BlockPos pos = BlockPlacement.shouldReplace(level, ground, yPos, nether)
                ? ground
                : ground.above();

        BlockState state = level.getBlockState(pos);
        boolean waterloggable = !nether && state.hasProperty(BlockStateProperties.WATERLOGGED);
        boolean waterlogged = waterloggable && state.getValue(BlockStateProperties.WATERLOGGED);

        // Upstream cancels before deciding whether anything actually needs placing, so a bot
        // landing on water it already placed still survives the fall.
        event.setCancelled(true);

        if (state.getBlock() == placeType || waterlogged) {
            return;
        }

        bot.punch();

        if (waterloggable) {
            level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.WATERLOGGED, true));
        } else {
            level.setBlockAndUpdate(pos, placeType.defaultBlockState());
        }

        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f);

        if (itemType != Items.WATER_BUCKET) {
            return;
        }

        bot.setItem(new ItemStack(Items.BUCKET));

        BlockPos pickup = pos;

        // later(), not a bare scheduler call: upstream used runTaskLater here without adding it
        // to its task list, so disabling the agent left the water behind.
        later(5, () -> {
            BlockState now = level.getBlockState(pickup);
            boolean loggedNow = now.getValueOrElse(BlockStateProperties.WATERLOGGED, false);

            if (now.getBlock() != Blocks.WATER && !loggedNow) {
                return;
            }

            bot.look(Direction.DOWN);
            bot.setItem(new ItemStack(Items.WATER_BUCKET));
            level.playSound(null, pickup, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1f, 1f);

            if (loggedNow) {
                level.setBlockAndUpdate(pickup, now.setValue(BlockStateProperties.WATERLOGGED, false));
            } else {
                level.setBlockAndUpdate(pickup, Blocks.AIR.defaultBlockState());
            }
        });
    }

    private static BlockState stateAt(ServerLevel level, Vec3 pos) {
        return level.getBlockState(BlockPos.containing(pos));
    }
}
