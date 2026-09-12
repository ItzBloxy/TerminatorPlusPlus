package net.nuggetmc.tplus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.command.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TerminatorPlus.MOD_ID)
public class TerminatorPlus {

    public static final String MOD_ID = "tplus";
    public static final Logger LOGGER = LoggerFactory.getLogger("TerminatorPlus");

    private static final BotRegistry REGISTRY = new BotRegistry();

    /**
     * FML injects the parameters by type. The two-argument form is what the official
     * 26.2 MDK uses; do not reduce it to a single argument.
     */
    public TerminatorPlus(IEventBus modEventBus, ModContainer modContainer) {
        // Game events (tick, commands, shutdown) live on the NeoForge bus, not the mod bus.
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("TerminatorPlus loading");
    }

    public static BotRegistry registry() {
        return REGISTRY;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        REGISTRY.tick();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BotCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Removing {} bot(s) on shutdown", REGISTRY.size());
        REGISTRY.reset();
    }
}
