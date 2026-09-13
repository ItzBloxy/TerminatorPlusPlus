package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.event.BotFallDamageEvent;
import net.nuggetmc.tplus.event.BotKilledByPlayerEvent;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.motion.BotPhysics;
import net.nuggetmc.tplus.motion.GroundCheck;
import net.nuggetmc.tplus.motion.MotionVec;
import net.nuggetmc.tplus.util.BotUtils;
import net.nuggetmc.tplus.util.ItemUtils;

import java.util.List;
import java.util.UUID;

/**
 * A server-side player bot.
 *
 * <p>Extends {@link ServerPlayer} directly rather than NeoForge's {@code FakePlayer}:
 * FakePlayer sets itself invulnerable and no-ops tick(), die() and canHarmPlayer(),
 * all of which a combat bot needs.
 *
 * <p>Ticking has two sources. The level drives {@link #tick()} for physics, and
 * {@code BotRegistry} drives the agent from ServerTickEvent. Plan A leaves the agent
 * out entirely, so a bot only falls and stands.
 */
public class Bot extends ServerPlayer {

    private final MotionVec velocity = new MotionVec();
    private MotionVec oldVelocity = new MotionVec();

    private int aliveTicks;
    private byte groundTicks;
    private byte jumpTicks;
    private byte noFallTicks = 60;

    private boolean inPlayerList;

    /**
     * What {@code setItem(null)} falls back to. Upstream initialised this to an AIR stack;
     * the vanilla equivalent is {@code ItemStack.EMPTY}, which {@code ItemUtils} scores as
     * bare fists — the same 0.25 damage the Paper build gave an AIR stack.
     */
    private ItemStack defaultItem = ItemStack.EMPTY;

    /**
     * A fixed random point in a radius-3 horizontal circle, chosen once per bot.
     *
     * <p>{@code LegacyAgent} adds it to the target's position when {@code offsets} is on, so a
     * group of bots converges on a ring around the target instead of a single point.
     */
    private final MotionVec offset = BotMath.circleOffset(3);

    private UUID targetPlayer;
    private int kills;

    private boolean shield;
    private boolean blocking;
    private boolean blockUse;

    /**
     * The registry that owns this bot. Set by {@link BotRegistry#add(Bot)} before the
     * bot enters the world, so tick failures and death route to the registry that
     * actually tracks it rather than to a global singleton — which would make a test
     * with its own registry silently exercise the wrong object.
     */
    private BotRegistry registry;

    public Bot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());

        this.connection = new ServerGamePacketListenerImpl(
                server,
                new BotConnection(),
                this,
                CommonListenerCookie.createInitial(profile, false));
    }

    void setRegistry(BotRegistry registry) {
        this.registry = registry;
    }

    /** Never null in practice; guarded because it is read from a catch block. */
    public BotRegistry getRegistry() {
        return registry;
    }

    /**
     * Bots are meant to be indistinguishable from human players; protection and PvP
     * mods commonly skip anything reporting true here, which would break combat.
     * See spec section 9 risk 4.
     */
    @Override
    public boolean isFakePlayer() {
        return false;
    }

    public MotionVec getBotVelocity() {
        return velocity;
    }

    public int getAliveTicks() {
        return aliveTicks;
    }

    public boolean isBotOnGround() {
        return groundTicks != 0;
    }

    public boolean isInPlayerList() {
        return inPlayerList;
    }

    void setInPlayerList(boolean value) {
        this.inPlayerList = value;
    }

    byte getGroundTicks() {
        return groundTicks;
    }

    void setGroundTicks(byte value) {
        this.groundTicks = value;
    }

    byte getJumpTicks() {
        return jumpTicks;
    }

    void setJumpTicks(byte value) {
        this.jumpTicks = value;
    }

    public byte getNoFallTicks() {
        return noFallTicks;
    }

    MotionVec getOldVelocity() {
        return oldVelocity;
    }

    void setOldVelocity(MotionVec value) {
        this.oldVelocity = value;
    }

    /** Applies the current velocity to the entity. Called from the physics step. */
    void applyMotion(double x, double y, double z) {
        this.move(MoverType.SELF, new Vec3(x, y, z));
    }

    /**
     * Keeps the 3x3 chunk neighbourhood around the bot loaded.
     *
     * <p>The Paper build wrote {@code chunk.loaded = true} directly; that field is
     * private as of 26.2 and {@code setLoaded} is the replacement.
     */
    void loadChunks() {
        // ChunkPos is a record in 26.2: x()/z() accessors, not public fields.
        int cx = chunkPosition().x();
        int cz = chunkPosition().z();

        for (int i = cx - 1; i <= cx + 1; i++) {
            for (int j = cz - 1; j <= cz + 1; j++) {
                level().getChunk(i, j).setLoaded(true);
            }
        }
    }

    /**
     * Removes the bot from the world and from clients.
     *
     * <p>clearTriggers() is the fix for NeoForge issue 1487: a fake player whose UUID
     * matches no real account leaves criterion listeners registered forever. NeoForge
     * ships FakePlayerAdvancements for this, but ServerPlayer.advancements is private
     * and final, so calling the public cleanup is the cheaper route.
     */
    public void removeBot() {
        BotFactory.despawn(this);

        getAdvancements().clearTriggers();

        // ServerPlayer.server is private, and vanilla Entity has no getServer() (that
        // was a Paper addition). Level.getServer() is the route in NeoForge.
        if (isInPlayerList()) {
            // Not getPlayers(): that is an unmodifiable view and removing through it throws.
            // This mirrors the insert in BotFactory.spawn, and was a latent crash until the
            // spawn path became reachable — it could not fire while spawn threw first.
            //
            // PlayerList.remove(ServerPlayer) is public and would do a tidier job, clearing
            // advancement triggers and broadcasting the info-remove packet. It is not used
            // because it also calls save(player), writing a playerdata file per bot, and fires
            // PlayerLoggedOut. Upstream fired neither.
            level().getServer().getPlayerList().players.remove(this);
            setInPlayerList(false);
        }

        remove(RemovalReason.DISCARDED);
    }

    // ---- rotation and looking ---------------------------------------------

    /** Pass-through. {@code Entity.getBoundingBox()} is public final, so this cannot override. */
    public AABB getBotBoundingBox() {
        return getBoundingBox();
    }

    public void setBotPitch(float pitch) {
        setXRot(pitch);
    }

    /**
     * Turns the bot to face {@code target}.
     *
     * <p>Ported from {@code Bot.faceLocation}. Upstream passed {@code keepYaw = false}, so
     * both yaw and pitch are recomputed and the head-rotation packet goes out.
     */
    public void faceLocation(Vec3 target) {
        look(MotionVec.of(target.subtract(position())), false);
    }

    /**
     * Turns the bot to face a block face.
     *
     * <p>Ported from {@code Bot.look(BlockFace)}. UP and DOWN keep the current yaw — a bot
     * looking at the block under its feet must not spin to face north to do it.
     *
     * <p>26.2 renamed the unit vector: {@code Direction.getUnitVec3()} replaces 1.21's
     * {@code step()}.
     */
    public void look(Direction face) {
        look(MotionVec.of(face.getUnitVec3()), face == Direction.DOWN || face == Direction.UP);
    }

    private void look(MotionVec dir, boolean keepYaw) {
        // Not upstream's: upstream could not reach this state because Bukkit's Vector threw
        // on a zero normalize and the exception unwound. MotionVec reproduces the NaN
        // faithfully instead (see spec §2.5), which means a zero direction would write NaN
        // into yRot and the bot would never aim again. Refuse it here, at the one place
        // every caller funnels through.
        if (dir.lengthSquared() == 0 || BotMath.isNotFinite(dir)) {
            return;
        }

        float yaw;
        float pitch;

        if (keepYaw) {
            yaw = getYRot();
            pitch = BotMath.fetchPitch(dir);
        } else {
            float[] vals = BotMath.fetchYawPitch(dir);
            yaw = vals[0];
            pitch = vals[1];

            setYHeadRot(yaw);
            BotFactory.broadcast(this, new ClientboundRotateHeadPacket(this, (byte) (yaw * 256 / 360f)));
        }

        // Entity.setRot is protected in 26.2 — reachable here because Bot is a subclass, but
        // not from Navigation. That is why turning is a Bot method and not a helper.
        setRot(yaw, pitch);
    }

    // ---- pose, animation and equipment ------------------------------------

    /** Swings the main hand. Vanilla broadcasts the animation packet for us. */
    public void punch() {
        swing(InteractionHand.MAIN_HAND);
    }

    public void swim() {
        setSwimming(true);
        registerPose(Pose.SWIMMING);
    }

    public void sneak() {
        // Bukkit's setSneaking is vanilla's setShiftKeyDown; both set the same shared flag.
        setShiftKeyDown(true);
        registerPose(Pose.CROUCHING);
    }

    public void stand() {
        setShiftKeyDown(false);
        setSwimming(false);
        registerPose(Pose.STANDING);
    }

    /**
     * Deliberately empty, and deliberately kept.
     *
     * <p>Upstream's body is two commented-out statements — it has never done anything. Making
     * it work would start syncing poses and, through {@code refreshDimensions}, start changing
     * the bot's hitbox when it crouches. That is a behaviour change, not a bug fix, so it is
     * out of scope for a faithful port. The parameter is retained so the call sites read the
     * same as upstream's.
     */
    @SuppressWarnings("unused")
    private void registerPose(Pose pose) {
    }

    public void setDefaultItem(ItemStack item) {
        this.defaultItem = item;
    }

    /** Main hand. A null {@code item} means "restore the default item". */
    public void setItem(ItemStack item) {
        setItem(item, EquipmentSlot.MAINHAND);
    }

    public void setItemOffhand(ItemStack item) {
        setItem(item, EquipmentSlot.OFFHAND);
    }

    /**
     * Ported from {@code Bot.setItem(ItemStack, EquipmentSlot)}.
     *
     * <p>{@code setItemSlot} is the vanilla route for both slots: {@code PlayerEquipment.set}
     * sends MAINHAND to {@code inventory.setSelectedItem} and everything else to the backing
     * equipment map, so the inventory and the equipment view cannot disagree. The Paper build
     * wrote the Bukkit inventory and then sent the packet; only the first half changes.
     *
     * <p>The equipment packet is still sent by hand, for the same reason the spawn packet is:
     * bots are not announced to clients the way tracked players are.
     */
    public void setItem(ItemStack item, EquipmentSlot slot) {
        ItemStack stack = item == null ? defaultItem : item;

        setItemSlot(slot, stack);

        BotFactory.broadcast(this, new ClientboundSetEquipmentPacket(
                getId(), List.of(Pair.of(slot, stack))));
    }

    /** Puts a shield in the offhand and allows {@link #block(int, int)} to fire. */
    public void setShield(boolean enabled) {
        this.shield = enabled;

        setItemOffhand(enabled ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
    }

    // ---- velocity ---------------------------------------------------------

    /**
     * The bot's velocity, as a <b>copy</b>.
     *
     * <p>This is not the same thing as {@link #getBotVelocity()}, and the difference matters.
     * {@code getBotVelocity} hands out the live vector because {@code BotPhysics.step} mutates
     * it in place; that is the physics path. Agent code mutates whatever it is given —
     * {@code Navigation.checkUp} does {@code getVelocity().add(v)} — so this path must copy or
     * the agent silently rewrites the bot's motion. Upstream returned {@code velocity.clone()}
     * here for exactly this reason.
     */
    public MotionVec getVelocity() {
        return getBotVelocity().copy();
    }

    /**
     * Replaces the velocity.
     *
     * <p>Upstream rebound the field. Ours is final, because {@code BotPhysics} captures it, so
     * this copies component-wise instead. Observably identical, and it keeps that reference
     * valid.
     */
    public void setVelocity(MotionVec vec) {
        MotionVec live = getBotVelocity();
        live.setX(vec.getX()).setY(vec.getY()).setZ(vec.getZ());
    }

    /**
     * Adds {@code vel}, then clamps the total to 0.4.
     *
     * <p>Ported from {@code Bot.walk}. The clamp is a normalize-and-scale, so a vector already
     * under the cap is unchanged.
     */
    public void walk(MotionVec vel) {
        double max = 0.4;

        MotionVec sum = getVelocity().add(vel);
        if (sum.length() > max) {
            sum.normalize().multiply(max);
        }

        setVelocity(sum);
    }

    // ---- combat -----------------------------------------------------------

    /**
     * Faces the target, swings, and applies 1.8 damage for whatever is in hand.
     *
     * <p>Ported from {@code Bot.attack}. The damage comes from {@link ItemUtils}, not from the
     * item's real attack-damage attribute — see that class for why.
     */
    public void attack(LivingEntity target) {
        faceLocation(target.position());
        punch();

        double damage = ItemUtils.getLegacyAttackDamage(defaultItem);

        target.hurtServer((ServerLevel) level(), damageSources().playerAttack(this), (float) damage);
    }

    /**
     * Raises the shield for {@code blockLength} ticks, then locks it out for {@code cooldown}.
     *
     * <p>Ported from {@code Bot.block}. Does nothing unless {@link #setShield(boolean)} put a
     * shield in the offhand, and nothing while a previous block is still on cooldown.
     *
     * <p>In v1 this has no caller: its only upstream caller is the neural-network branch of
     * {@code tickBot} (plan correction 4). It is ported anyway because {@code hurtServer}
     * consults {@code blocking}, and a shield that can never be raised would make that branch
     * untestable.
     */
    public void block(int blockLength, int cooldown) {
        if (!shield || blockUse) {
            return;
        }

        startBlocking();

        if (registry != null) {
            registry.scheduler().runLater(blockLength, () -> stopBlocking(cooldown));
        }
    }

    private void startBlocking() {
        this.blocking = true;
        this.blockUse = true;

        startUsingItem(InteractionHand.OFF_HAND);
        BotFactory.broadcast(this, new ClientboundSetEntityDataPacket(getId(), getEntityData().packDirty()));
    }

    private void stopBlocking(int cooldown) {
        this.blocking = false;

        stopUsingItem();

        if (registry != null) {
            registry.scheduler().runLater(cooldown, () -> this.blockUse = false);
        }

        BotFactory.broadcast(this, new ClientboundSetEntityDataPacket(getId(), getEntityData().packDirty()));
    }

    /**
     * Whether the bot is blocking. <b>Always false.</b>
     *
     * <p>Upstream delegated to vanilla {@code isBlocking()} rather than reading its own
     * {@code blocking} flag, and the two disagree — permanently. Vanilla's
     * {@code getItemBlockingWith} requires {@code blockDelayTicks} to have elapsed since the
     * item went into use, measured from {@code useItemRemaining}; that field is only
     * decremented by {@code updateUsingItem}, which is only reached from
     * {@code LivingEntity.tick()}, which never runs for a bot — {@code ServerPlayer.tick()}
     * does not call it and {@code doTick()} calls only {@code detectEquipmentUpdates} and
     * {@code baseTick}. So the elapsed count stays at zero and this never returns true.
     *
     * <p>That is upstream's behaviour too: its {@code doTick} was identical and its 1.21
     * {@code isBlocking} had the same 5-tick warmup. The shield feature has never worked, in
     * either codebase. Ported faithfully rather than fixed, because making it work means
     * changing what a bot ticks, which changes far more than shields. A GameTest pins the dead
     * state so nobody half-fixes it.
     *
     * <p>The private {@code blocking} flag is a different thing and does become true: the
     * damage path reads it to play the block sound, exactly as upstream's {@code hurt} did.
     */
    public boolean isBotBlocking() {
        return isBlocking();
    }

    // ---- state ------------------------------------------------------------

    public MotionVec getOffset() {
        return offset;
    }

    public boolean isFalling() {
        return getBotVelocity().getY() < -0.8;
    }

    /** True every {@code i}th tick of the bot's life. {@code aliveTicks} starts at 0. */
    public boolean tickDelay(int i) {
        return getAliveTicks() % i == 0;
    }

    public boolean isBotAlive() {
        return isAlive();
    }

    public boolean isBotOnFire() {
        return isOnFire();
    }

    public String getBotName() {
        return getGameProfile().name();
    }

    /**
     * True in the Nether.
     *
     * <p>Upstream's {@code getDimension()} returned Bukkit's {@code World.Environment} and
     * every one of its five call sites compared it to {@code NETHER}, so the predicate is the
     * faithful translation of the accessor.
     */
    public boolean isNether() {
        return level().dimension() == Level.NETHER;
    }

    public UUID getTargetPlayer() {
        return targetPlayer;
    }

    public void setTargetPlayer(UUID target) {
        this.targetPlayer = target;
    }

    /**
     * Pass-throughs so callers outside this package read a bot's health without touching the
     * {@code LivingEntity} surface. Deferred from task 4 because nothing read them until
     * {@code /tplus info}.
     */
    public float getBotHealth() {
        return getHealth();
    }

    public float getBotMaxHealth() {
        return getMaxHealth();
    }

    public int getKills() {
        return kills;
    }

    public void incrementKills() {
        kills++;
    }

    /**
     * Places {@code type} at {@code pos} if nothing solid is there, with the animation of a
     * player doing it.
     *
     * <p>Ported from {@code Bot.attemptBlockPlace}. Two details are upstream's and look wrong
     * but are not: the bot always puts <b>cobblestone</b> in hand regardless of {@code type},
     * and the sound is always the stone place sound.
     *
     * <p>The solidity guard is upstream's {@code LegacyMats.isSolid}. Until Task 13 builds
     * {@code BlockRules}, this uses the vanilla predicate, which is the same thing for every
     * block upstream's version did not special-case. Task 13 replaces this line.
     */
    public void attemptBlockPlace(BlockPos pos, Block type, boolean down) {
        if (down) {
            look(Direction.DOWN);
        } else {
            faceLocation(Vec3.atCenterOf(pos));
        }

        setItem(new ItemStack(Items.COBBLESTONE));
        punch();

        ServerLevel level = (ServerLevel) level();
        BlockState state = level.getBlockState(pos);

        if (!state.isSolid()) {
            level.setBlockAndUpdate(pos, type.defaultBlockState());
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1f, 1f);
        }
    }

    /**
     * The agent that owns this bot, never null.
     *
     * <p>A bot always has a registry in practice — {@code BotFactory.spawn} registers before the
     * bot enters the level — but the damage path must not NPE if one somehow does not, so an
     * orphan gets a shared no-op.
     */
    public Agent agent() {
        return registry != null ? registry.agent() : ORPHAN_AGENT;
    }

    private static final Agent ORPHAN_AGENT = Agent.noop(null);

    // ---- damage -----------------------------------------------------------

    /**
     * Ported from {@code Bot.hurt(DamageSource, float)}. 26.2 renamed the server-side entry
     * point to {@code hurtServer} and threads the level through it.
     *
     * <p>Four behaviours live here, in upstream's order: the player-damage event (which can
     * veto or soften the hit), the shield-block sound when the hit is refused, knockback for a
     * hit the bot survives, and the kill credit for one it does not.
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        Entity attacker = source.getEntity();

        // Deliberately NOT `&& !(attacker instanceof Bot)`. Bot extends ServerPlayer, so
        // upstream's `attacker instanceof ServerPlayer` was true for a bot attacker too —
        // and that is load-bearing: it is how bot-versus-bot kills get counted, through
        // Agent.onBotKilledByPlayer looking the killer up in the registry.
        boolean fromPlayer = attacker instanceof ServerPlayer;

        float damage = amount;
        ServerPlayer killer = null;

        if (fromPlayer) {
            killer = (ServerPlayer) attacker;

            BotDamageByPlayerEvent event = new BotDamageByPlayerEvent(this, killer, amount);
            agent().onPlayerDamage(event);

            if (event.isCancelled()) {
                return false;
            }

            damage = event.getDamage();
        }

        boolean damaged = super.hurtServer(level, source, damage);

        // Upstream keyed this off its own `blocking` flag rather than vanilla isBlocking(),
        // and the two can disagree — see isBotBlocking. Kept as upstream had it.
        if (!damaged && blocking) {
            level.playSound(null, blockPosition(), SoundEvents.SHIELD_BLOCK.value(),
                    SoundSource.PLAYERS, 1f, 1f);
        }

        if (damaged && attacker != null) {
            if (fromPlayer && !isAlive()) {
                agent().onBotKilledByPlayer(new BotKilledByPlayerEvent(this, killer));
            } else {
                kb(position(), attacker.position(), attacker);
            }
        }

        return damaged;
    }

    /**
     * Knockback, ported from {@code Bot.kb}.
     *
     * <p>Two upstream oddities, both preserved. It <b>replaces</b> the velocity rather than
     * adding to it, so a hit cancels whatever the bot was doing. And it reads the Knockback
     * enchantment off the attacker's main hand, which is the only place in the whole plugin that
     * any enchantment is consulted.
     */
    private void kb(Vec3 self, Vec3 attackerPos, Entity attacker) {
        MotionVec vel = MotionVec.of(self.subtract(attackerPos)).setY(0).normalize().multiply(0.3);

        if (isBotOnGround()) {
            vel.multiply(0.8).setY(0.4);
        }

        // Player, not LivingEntity. Upstream wrote the test twice, redundantly, and both
        // times as `attacker.getBukkitEntity() instanceof Player` — so a mob swinging a
        // Knockback sword gets no boost. Broadening it here would change mob combat.
        if (attacker instanceof Player living) {
            int level = knockbackLevel(living);

            if (level == 1) {
                vel.multiply(1.05).setY(0.4);
            } else if (level > 1) {
                vel.multiply(1.9).setY(0.4);
            }
        }

        // A hit from exactly the bot's own position normalises to NaN, and setVelocity would
        // write that straight into the physics vector. Upstream could not reach this because
        // Bukkit's Vector threw instead; see the same guard in look().
        if (BotMath.isNotFinite(vel)) {
            BotMath.clean(vel);
        }

        setVelocity(vel);
    }

    /**
     * Knockback enchantment level on the attacker's main hand, or 0.
     *
     * <p>26.2 keeps enchantments in a data component and looks them up through a registry
     * holder, so the Bukkit {@code ItemMeta.hasEnchant} test becomes a registry lookup plus an
     * {@code EnchantmentHelper} query.
     */
    private int knockbackLevel(Player attacker) {
        return attacker.level().registryAccess()
                .lookup(Registries.ENCHANTMENT)
                .flatMap(registry -> registry.get(Enchantments.KNOCKBACK))
                .map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, attacker.getMainHandItem()))
                .orElse(0);
    }

    void incrementAliveTicks() {
        aliveTicks++;
    }

    void decrementTimers() {
        if (jumpTicks > 0) --jumpTicks;
        if (noFallTicks > 0) --noFallTicks;
    }

    // ---- ticking, damage, death -------------------------------------------

    private static final float REGEN_PER_TICK = 0.025f;

    private List<BlockPos> standingOn = List.of();
    private boolean removeOnDeath = true;

    /**
     * The level drives this every tick. It is wrapped because a throw here propagates
     * straight into {@code ServerLevel.tickNonPassenger} and crashes the server — which
     * is exactly what an empty-VoxelShape bug did before this guard existed.
     * BotRegistry's isolation covers only the agent hook, not this path.
     */
    @Override
    public void tick() {
        try {
            tickInternal();
            if (registry != null) {
                registry.clearTickFailures(this);
            }
        } catch (Throwable t) {
            if (registry != null) {
                registry.noteTickFailure(this, t);
            } else {
                // Should not happen: the registry is set before the bot enters the world.
                TerminatorPlus.LOGGER.error("Untracked bot '{}' failed its tick",
                        getGameProfile().name(), t);
            }
        }
    }

    private void tickInternal() {
        loadChunks();

        super.tick();

        if (!isAlive()) {
            return;
        }

        incrementAliveTicks();
        decrementTimers();

        if (checkGround()) {
            if (getGroundTicks() < 5) {
                setGroundTicks((byte) (getGroundTicks() + 1));
            }
        } else {
            setGroundTicks((byte) 0);
        }

        updateLocation();

        if (!isAlive()) {
            return;
        }

        regenerate();
        fallDamageCheck();

        setOldVelocity(getBotVelocity().copy());

        doTick();
    }

    private void regenerate() {
        float health = getHealth();
        float max = getMaxHealth();

        setHealth(health < max - REGEN_PER_TICK ? health + REGEN_PER_TICK : max);
    }

    private void updateLocation() {
        MotionVec velocity = getBotVelocity();
        double y = BotPhysics.step(velocity, getGroundTicks(), getJumpTicks(), isBotInWater());

        applyMotion(velocity.getX(), y, velocity.getZ());
    }

    public boolean isBotInWater() {
        // Matches the original exactly: probe feet, waist and head, and test the BLOCK
        // identity rather than the fluid state. Those differ — a waterlogged stair has a
        // non-empty FluidState but is not Material.WATER, so a getFluidState().isEmpty()
        // check would report true where the Paper build reported false.
        for (int i = 0; i <= 2; i++) {
            BlockPos pos = BlockPos.containing(getX(), getY() + (i * 0.9), getZ());
            Block block = level().getBlockState(pos).getBlock();

            if (block == Blocks.WATER || block == Blocks.LAVA) {
                return true;
            }
        }

        return false;
    }

    private boolean checkGround() {
        if (getBotVelocity().getY() > 0) {
            return false;
        }

        standingOn = GroundCheck.standingOn(
                (ServerLevel) level(), getBoundingBox(), position().y, getBbHeight());
        return !standingOn.isEmpty();
    }

    public List<BlockPos> getStandingOn() {
        return standingOn;
    }

    private void fallDamageCheck() {
        if (getGroundTicks() == 0 || getNoFallTicks() != 0) {
            return;
        }

        double oldY = getOldVelocity().getY();
        if (oldY >= -0.8) {
            return;
        }

        if (isFallBlocked()) {
            return;
        }

        // The copy is upstream's (new ArrayList<>(getStandingOn())) and matters: the handler
        // places blocks, which makes checkGround recompute standingOn underneath it.
        BotFallDamageEvent event = new BotFallDamageEvent(this, List.copyOf(getStandingOn()));
        agent().onFallDamage(event);

        if (!event.isCancelled()) {
            hurtServer((ServerLevel) level(), damageSources().fall(), (float) Math.pow(3.6, -oldY));
        }
    }

    /**
     * True when the bot is landing in something that cancels fall damage — water, lava,
     * cobweb, powder snow, vines, sweet berries, or any waterlogged block.
     *
     * <p>Ported from {@code Bot.isFallBlocked}. The odd-looking {@code maxX - 0.01} and
     * {@code Math.floor} are upstream's; they are preserved.
     */
    private boolean isFallBlocked() {
        AABB box = getBoundingBox();
        double[] xs = {box.minX, box.maxX - 0.01};
        double[] zs = {box.minZ, box.maxZ - 0.01};

        AABB botBox = new AABB(box.minX, position().y - 0.01, box.minZ,
                box.maxX, position().y + getBbHeight(), box.maxZ);

        for (double x : xs) {
            for (double z : zs) {
                BlockPos pos = BlockPos.containing(Math.floor(x), getY(), Math.floor(z));
                BlockState state = level().getBlockState(pos);

                if (state.getValueOrElse(BlockStateProperties.WATERLOGGED, false)) {
                    return true;
                }

                Block block = state.getBlock();
                if (!BotUtils.NO_FALL.contains(block)) {
                    continue;
                }

                // Water and lava short-circuit, exactly as upstream did.
                if (block == Blocks.WATER || block == Blocks.LAVA) {
                    return true;
                }

                // Outline shape, not collision: Bukkit's getBoundingBox() maps to
                // getShape, and cobweb/vines/powder snow have empty collision shapes.
                // bounds() throws on an empty shape, so the check is mandatory.
                VoxelShape voxel = state.getShape(level(), pos);
                if (voxel.isEmpty()) {
                    continue;
                }

                if (botBox.intersects(voxel.bounds().move(pos))) {
                    return true;
                }
            }
        }

        return false;
    }

    public void jump(MotionVec impulse) {
        if (getJumpTicks() == 0 && getGroundTicks() > 1) {
            setJumpTicks((byte) 4);
            getBotVelocity().setX(impulse.getX()).setY(impulse.getY()).setZ(impulse.getZ());
        }
    }

    public void jump() {
        jump(new MotionVec(0, 0.42, 0));
    }

    /** Adds to the bot's velocity, discarding non-finite input as the original did. */
    public void addVelocity(MotionVec delta) {
        if (BotMath.isNotFinite(delta)) {
            getBotVelocity().setX(delta.getX()).setY(delta.getY()).setZ(delta.getZ());
            return;
        }

        getBotVelocity().add(delta);
    }

    public void setRemoveOnDeath(boolean value) {
        this.removeOnDeath = value;
    }

    /**
     * Ported from {@code die} + {@code dieCheck}. The delay matters: the bot is
     * unregistered and hidden immediately, but the entity is not discarded for another
     * 20 ticks so the death animation can play out clientside.
     *
     * <p>Sending the despawn packets alone would leave the entity in the world forever.
     */
    @Override
    public void die(DamageSource cause) {
        super.die(cause);

        if (!removeOnDeath) {
            return;
        }

        BotFactory.despawn(this);

        if (registry != null) {
            registry.remove(this);
            registry.scheduler().runLater(20, this::removeBot);
        } else {
            removeBot();
        }
    }

    /**
     * Knockback. Note: the Paper original computed both axes from getX()/getZ(), a
     * copy-paste bug. Ported verbatim per the spec's faithful-translation rule; see
     * spec section 5 step 3. Do not fix it here.
     */
    @Override
    public void push(Entity entity) {
        if (isPassengerOfSameVehicle(entity) || entity.noPhysics || this.noPhysics) {
            return;
        }

        double d0 = entity.getX() - this.getZ();
        double d1 = entity.getX() - this.getZ();
        double d2 = Mth.absMax(d0, d1);

        if (d2 < 0.009999999776482582D) {
            return;
        }

        d2 = Math.sqrt(d2);
        d0 /= d2;
        d1 /= d2;

        double scale = Math.min(1.0D / d2, 1.0D);
        d0 *= scale * 0.05000000074505806D;
        d1 *= scale * 0.05000000074505806D;

        if (!this.isVehicle()) {
            getBotVelocity().add(-d0, 0.0D, -d1);
        }

        if (!entity.isVehicle()) {
            entity.push(d0, 0.0D, d1);
        }
    }

    @Override
    public void doTick() {
        // detectEquipmentUpdatesPublic() was a Paper addition; vanilla's
        // detectEquipmentUpdates() is public as of 26.2.
        detectEquipmentUpdates();
        baseTick();
    }
}
