package net.nuggetmc.tplus.bot;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A {@link Connection} that goes nowhere, so a {@code ServerPlayer} can exist without
 * a socket. Modelled on NeoForge's own {@code FakePlayer.FakeConnection}, which is
 * package-private and cannot be reused.
 *
 * <p>This replaces the Paper build's MockConnection (68 lines) and MockChannel (81
 * lines). Both existed largely to defeat obfuscation: MockConnection reflected on
 * {@code Connection.class.getDeclaredField("q")}. Minecraft 26.1 dropped server
 * obfuscation, so none of that is needed.
 *
 * <p>Unlike NeoForge's FakePlayer, bots here can be added to the real
 * {@code PlayerList}, so the server will genuinely try to send them packets. Every
 * {@code send} is therefore explicitly swallowed.
 */
public final class BotConnection extends Connection {

    public BotConnection() {
        super(PacketFlow.SERVERBOUND);

        // Give the connection a real netty channel that goes nowhere.
        //
        // Constructing an EmbeddedChannel around this handler fires channelActive, which is
        // where Connection assigns its `channel` field. Without it channel() is null, and
        // anything that asks the connection about its channel NPEs.
        //
        // That is not hypothetical and it is not something the GameTest server reproduces:
        // on a dedicated server NeoForge's ConfigSync.syncPendingConfigs runs on every
        // ServerTickEvent.Post, walks the PlayerList, and calls hasChannel on each player's
        // listener, which reads a netty attribute off this channel. A bot in the real
        // PlayerList crashed the server on its first tick before this existed.
        //
        // Nothing is ever written to the channel — every send() below is swallowed — so the
        // EmbeddedChannel's outbound queue stays empty. Minecraft's own GameTestHelper builds
        // its mock player connections the same way.
        new EmbeddedChannel(this);
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
        // No handshake will ever arrive.
    }

    @Override
    public void send(Packet<?> packet) {
        // Discarded: there is no client behind this connection.
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public boolean isConnected() {
        return true;
    }
}
