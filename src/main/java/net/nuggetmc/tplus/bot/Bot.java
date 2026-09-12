package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.motion.MotionVec;

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
}
