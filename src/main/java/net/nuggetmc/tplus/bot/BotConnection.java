package net.nuggetmc.tplus.bot;

import io.netty.channel.ChannelFutureListener;
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
