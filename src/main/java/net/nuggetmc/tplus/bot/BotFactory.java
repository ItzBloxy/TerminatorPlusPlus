package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Spawns bots and makes them visible to real clients.
 *
 * <p>Visibility is manual: a bot added with {@code addFreshEntity} is a player entity
 * the vanilla tracker will not announce to clients as a player, so the tab-list entry
 * and spawn packet are sent by hand. Skins ride along in the GameProfile, which is why
 * no client mod is needed.
 */
public final class BotFactory {

    private BotFactory() {
    }

    /**
     * Creates a bot, registers it, and puts it in the world.
     *
     * <p>Registration happens before the bot enters the level so that its very first
     * tick already has a registry to report failures to. Taking the registry as a
     * parameter rather than reading a global also means a test cannot accidentally
     * exercise a different registry than the one it asserts on.
     *
     * @param addToPlayerList when true the bot joins the real PlayerList, so the server
     *                        treats it as an online player. That path is riskier (spec
     *                        section 9 risk 1); callers should default to false.
     */
    public static Bot spawn(BotRegistry registry, ServerLevel level, Vec3 pos, float yaw, float pitch,
                            GameProfile profile, boolean addToPlayerList) {
        MinecraftServer server = level.getServer();

        Bot bot = new Bot(server, level, profile);
        bot.setPos(pos.x, pos.y, pos.z);
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);

        registry.add(bot);

        if (addToPlayerList) {
            // Spec risk 1, resolved by a GameTest: this path cannot work on NeoForge.
            // PlayerList.getPlayers() returns Collections.unmodifiableList(players) —
            // "Neo: Return an unmodifiable view, we don't want people removing things
            // without us knowing" — so the Paper build's getPlayers().add(bot) throws
            // UnsupportedOperationException here.
            //
            // Doing this properly means going through PlayerList.placeNewPlayer, the real
            // join path, which also sends login packets, fires events and loads playerdata
            // against a connection that goes nowhere. That is its own piece of work; it is
            // not something to bodge with an access transformer against an intentional
            // guard. Deferred to Plan B.
            throw new UnsupportedOperationException(
                    "Adding bots to the PlayerList is not supported on NeoForge: "
                            + "PlayerList.getPlayers() is an unmodifiable view. This needs "
                            + "PlayerList.placeNewPlayer support (deferred to Plan B).");
        } else {
            level.addFreshEntity(bot);
            broadcast(bot, new ClientboundPlayerInfoUpdatePacket(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, bot));
        }

        render(bot);
        return bot;
    }

    /** Sends the packets every client needs in order to draw this bot. */
    public static void render(Bot bot) {
        for (Packet<?> packet : renderPackets(bot)) {
            broadcast(bot, packet);
        }
    }

    /**
     * Sends the packets one client needs in order to draw {@code bot}.
     *
     * <p>Upstream's {@code onJoin} path. The delay on the final packet is upstream's too: a
     * client that has only just finished logging in discards entity data sent in the same tick,
     * and renders the bot as a default skin with no equipment.
     */
    public static void renderTo(Bot bot, ServerPlayer target, boolean login) {
        Packet<?>[] packets = renderPackets(bot);

        target.connection.send(packets[0]);
        target.connection.send(packets[1]);

        BotRegistry registry = bot.getRegistry();

        if (login && registry != null) {
            registry.scheduler().runLater(10, () -> target.connection.send(packets[2]));
        } else {
            target.connection.send(packets[2]);
        }
    }

    /** Add-entity, entity-data, rotate-head — in that order. */
    private static Packet<?>[] renderPackets(Bot bot) {
        return new Packet<?>[]{
                new ClientboundAddEntityPacket(
                        bot.getId(),
                        bot.getUUID(),
                        bot.getX(), bot.getY(), bot.getZ(),
                        bot.getXRot(), bot.getYRot(),
                        bot.getType(),
                        0,
                        bot.getDeltaMovement(),
                        bot.getYHeadRot()),

                // getNonDefaultValues() replaces the Paper build's NMSUtils, which reflected on
                // a private Int2ObjectMap that no longer exists in 26.2.
                new ClientboundSetEntityDataPacket(
                        bot.getId(), bot.getEntityData().getNonDefaultValues()),

                new ClientboundRotateHeadPacket(bot, (byte) (bot.getYHeadRot() * 256f / 360f))
        };
    }

    /** Removes the bot from clients: entity first, then the tab-list entry. */
    public static void despawn(Bot bot) {
        broadcast(bot, new ClientboundRemoveEntitiesPacket(bot.getId()));
        broadcast(bot, new ClientboundPlayerInfoRemovePacket(List.of(bot.getUUID())));
    }

    /** Sends a packet to every real player on the server, skipping bots. */
    public static void broadcast(Bot source, Packet<?> packet) {
        MinecraftServer server = source.level().getServer();
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof Bot) {
                continue;
            }
            player.connection.send(packet);
        }
    }
}
