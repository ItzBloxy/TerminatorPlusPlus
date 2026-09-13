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
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.event.BotDeathEvent;
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

        // The agent the registry drives. Swappable: BotRegistry starts with a no-op, and a
        // GameTest installs its own.
        REGISTRY.setAgent(new LegacyAgent(REGISTRY));
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
        BotCommands.register(event);
    }

    /**
     * Bridges NeoForge's drop event into {@code BotDeathEvent}.
     *
     * <p>{@code LivingDropsEvent} is the closest thing vanilla has to Bukkit's staged drop list,
     * and it is the only point at which clearing the drops still suppresses them.
     */
    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Bot bot) {
            // bot.agent(), not REGISTRY.agent(): a bot belongs to the registry that spawned it,
            // and a GameTest uses its own. Routing through the global registry would dispatch a
            // test's bot death to the production agent.
            bot.agent().onBotDeath(new BotDeathEvent(bot, event.getSource(), event.getDrops()));
        }
    }

    /**
     * Stops mobs picking bots as a target unless it has been turned on.
     *
     * <p>Upstream's {@code onMobTarget}. NeoForge's {@code LivingChangeTargetEvent} is fired for
     * exactly this, and cancelling it leaves the previous target in place, which is what Bukkit's
     * cancellation did too.
     */
    @SubscribeEvent
    public void onChangeTarget(LivingChangeTargetEvent event) {
        if (!(event.getNewAboutToBeSetTarget() instanceof Bot bot)) {
            return;
        }

        // The bot's own registry owns the flag, for the same reason onLivingDrops uses
        // bot.agent(). A bot with no registry cannot be reached by this path in practice.
        BotRegistry owner = bot.getRegistry();

        if (owner != null && !owner.isMobTarget()) {
            event.setCanceled(true);
        }
    }

    /**
     * Renders every live bot to a player who has just joined.
     *
     * <p>Upstream's {@code onJoin}. A client that was not connected when a bot spawned has never
     * been sent its spawn packets, so without this the bot is invisible to them.
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof Bot) {
            return;
        }

        for (Bot bot : REGISTRY.bots()) {
            BotFactory.renderTo(bot, player, true);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Removing {} bot(s) on shutdown", REGISTRY.size());
        REGISTRY.reset();
    }
}
