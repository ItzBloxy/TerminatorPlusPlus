package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
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

        // Survival, explicitly. ServerPlayer's constructor gives a new player whatever the host
        // defaults to, and on a GameTest server that is CREATIVE -- which sets
        // abilities.invulnerable and makes the bot immune to arrows, fire, fall damage and
        // everything else that is not a direct hurtServer call. Melee still works, because the
        // attacker names the entity rather than hitting a hitbox, which is why this survived
        // three plans: every test papered over it with a setGameMode call of its own.
        //
        // It is also what the goals assume. PlayerUtils.isInvincible treats anything but
        // SURVIVAL and ADVENTURE as invincible, so a CREATIVE bot is skipped by the vulnerable
        // player goal as well.
        bot.setGameMode(GameType.SURVIVAL);

        bot.setPos(pos.x, pos.y, pos.z);
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);

        registry.add(bot);

        if (addToPlayerList) {
            // Upstream did this, and only this: insert, announce, add to the level.
            //
            // Not placeNewPlayer. That is the supported join path, and it does far more —
            // it rebinds player.connection to a fresh listener around a real Connection,
            // sets up the inbound protocol, sends the login/difficulty/abilities packets,
            // syncs datapacks, recipes, the recipe book and the scoreboard, teleports the
            // player, fires OnDatapackSyncEvent and PlayerLoggedInEvent, and broadcasts
            // "<name> joined the game" to everyone. Upstream fired none of that, and the
            // join message alone makes spawning a hundred bots unusable.
            //
            // `players` is reachable only through an access transformer, because NeoForge
            // narrowed getPlayers() to an unmodifiable view on purpose. See the AT file.
            PlayerList list = server.getPlayerList();

            list.players.add(bot);
            bot.setInPlayerList(true);

            broadcast(bot, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot)));
            level.addNewPlayer(bot);

            // Deliberately NOT also putting the bot in playersByUUID. Upstream did not, so
            // getPlayer(uuid) does not find a playerlist bot, and that asymmetry is upstream's.
            // It looks exactly like an oversight to fix; doing so would change which entities
            // vanilla systems can resolve by UUID, and nothing in this port needs it.
        } else {
            level.addFreshEntity(bot);
            broadcast(bot, new ClientboundPlayerInfoUpdatePacket(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, bot));
        }

        render(bot);
        return bot;
    }

    /**
     * Sends the packets every client needs in order to draw this bot.
     *
     * <p>Skips the player-info packet: {@code spawn} broadcasts that itself, and sending it
     * twice is what upstream's join path did by accident.
     */
    public static void render(Bot bot) {
        Packet<?>[] packets = renderPackets(bot);

        for (int i = 1; i < packets.length; i++) {
            broadcast(bot, packets[i]);
        }
    }

    /**
     * Sends the packets one client needs in order to draw {@code bot}.
     *
     * <p>Upstream's {@code onJoin} path, with one bug fixed. Upstream sent
     * {@code [ADD_PLAYER, ADD_PLAYER, entity data]} and then the head rotation — the same
     * player-info packet twice, and <b>no spawn packet at all</b>, so a bot that existed before
     * you logged in never appeared. An earlier draft of this port had the mirror-image bug: it
     * sent the spawn packet but no player info, which renders the bot with no skin and no name.
     * A client needs all four, and that is what {@code spawn} already sends to everyone.
     *
     * <p>The delay on the last packet is upstream's and is real: a client that has only just
     * finished logging in discards entity data sent in the same tick.
     */
    public static void renderTo(Bot bot, ServerPlayer target, boolean login) {
        Packet<?>[] packets = renderPackets(bot);

        for (int i = 0; i < packets.length - 1; i++) {
            target.connection.send(packets[i]);
        }

        Packet<?> last = packets[packets.length - 1];
        BotRegistry registry = bot.getRegistry();

        if (login && registry != null) {
            registry.scheduler().runLater(10, () -> target.connection.send(last));
        } else {
            target.connection.send(last);
        }
    }

    /** Player info, add-entity, entity-data, rotate-head — in that order. */
    private static Packet<?>[] renderPackets(Bot bot) {
        return new Packet<?>[]{
                new ClientboundPlayerInfoUpdatePacket(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, bot),

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

    /**
     * Sends the block-crack overlay to every real player.
     *
     * <p>{@code id} is an arbitrary per-block animation id, so two bots cracking two blocks do
     * not overwrite each other; {@code stage} runs 0 to 9, and -1 clears the overlay.
     *
     * <p>Replaces the Paper build's {@code InternalBridgeImpl.sendBlockDestructionPacket} and
     * the whole {@code TerminatorPlusAPI.getInternalBridge()} indirection with it.
     */
    public static void broadcastCrack(Bot source, int id, BlockPos pos, int stage) {
        broadcast(source, new ClientboundBlockDestructionPacket(id, pos, stage));
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
