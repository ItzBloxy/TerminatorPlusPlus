package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.motion.BotPhysics;
import net.nuggetmc.tplus.motion.GroundCheck;
import net.nuggetmc.tplus.motion.MotionVec;

import java.util.List;
import java.util.Set;

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

    public Bot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());

        this.connection = new ServerGamePacketListenerImpl(
                server,
                new BotConnection(),
                this,
                CommonListenerCookie.createInitial(profile, false));
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

    byte getNoFallTicks() {
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
            level().getServer().getPlayerList().getPlayers().remove(this);
            setInPlayerList(false);
        }

        remove(RemovalReason.DISCARDED);
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

    /**
     * Blocks that cancel fall damage. Ported from {@code BotUtils.NO_FALL}; the Paper
     * build listed Materials, these are the equivalent Blocks.
     */
    private static final Set<Block> NO_FALL = Set.of(
            Blocks.WATER, Blocks.LAVA,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
            Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
            Blocks.COBWEB, Blocks.VINE);

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
            TerminatorPlus.registry().clearTickFailures(this);
        } catch (Throwable t) {
            TerminatorPlus.registry().noteTickFailure(this, t);
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

        standingOn = GroundCheck.standingOn((ServerLevel) level(), getBoundingBox(), getBbHeight());
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

        hurtServer((ServerLevel) level(), damageSources().fall(), (float) Math.pow(3.6, -oldY));
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
                if (!NO_FALL.contains(block)) {
                    continue;
                }

                // Water, lava, cobweb and vines all have empty collision shapes, and
                // VoxelShape.bounds() throws on those. The block-identity check below
                // is what actually matters for them; the intersect test only applies to
                // NO_FALL blocks that do collide.
                VoxelShape voxel = state.getCollisionShape(level(), pos);
                if (block == Blocks.WATER || block == Blocks.LAVA) {
                    return true;
                }
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

        TerminatorPlus.registry().remove(this);
        BotFactory.despawn(this);
        TerminatorPlus.registry().scheduler().runLater(20, this::removeBot);
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
