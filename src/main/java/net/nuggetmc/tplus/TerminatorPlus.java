package net.nuggetmc.tplus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TerminatorPlus.MOD_ID)
public class TerminatorPlus {

    public static final String MOD_ID = "tplus";
    public static final Logger LOGGER = LoggerFactory.getLogger("TerminatorPlus");

    public TerminatorPlus(IEventBus modBus) {
        LOGGER.info("TerminatorPlus loading");
    }
}
