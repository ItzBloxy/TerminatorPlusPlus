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
            server.getPlayerList().getPlayers().add(bot);
            bot.setInPlayerList(true);
            level.addNewPlayer(bot);
            broadcast(bot, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot)));
        } else {
            level.addFreshEntity(bot);
            broadcast(bot, new ClientboundPlayerInfoUpdatePacket(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, bot));
        }

        render(bot);
        return bot;
    }

    /** Sends the packets a client needs in order to draw this bot. */
    public static void render(Bot bot) {
        broadcast(bot, new ClientboundAddEntityPacket(
                bot.getId(),
                bot.getUUID(),
                bot.getX(), bot.getY(), bot.getZ(),
                bot.getXRot(), bot.getYRot(),
                bot.getType(),
                0,
                bot.getDeltaMovement(),
                bot.getYHeadRot()));

        // getNonDefaultValues() replaces the Paper build's NMSUtils, which reflected on
        // a private Int2ObjectMap that no longer exists in 26.2.
        broadcast(bot, new ClientboundSetEntityDataPacket(
                bot.getId(), bot.getEntityData().getNonDefaultValues()));

        broadcast(bot, new ClientboundRotateHeadPacket(bot, (byte) (bot.getYHeadRot() * 256f / 360f)));
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
