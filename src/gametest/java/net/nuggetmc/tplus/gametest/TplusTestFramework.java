package net.nuggetmc.tplus.gametest;

import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.testframework.conf.FrameworkConfiguration;
import net.neoforged.testframework.impl.MutableTestFramework;
import net.nuggetmc.tplus.TerminatorPlus;

/**
 * Stands up the NeoForge test framework for this mod's in-world tests.
 *
 * <p>This lives in the {@code gametest} source set, which the release {@code jar} task
 * does not include. The framework and the tests are therefore dev-only, and production
 * never needs the {@code testframework} dependency on its classpath.
 *
 * <p>Because {@code main} cannot see this source set, the mod class cannot call
 * {@code init} directly. Instead this registers itself through annotation scanning: the
 * source set is bound to the {@code tplus} mod id in build.gradle, so FML finds this
 * {@link EventBusSubscriber} and the framework's own scan finds the
 * {@code @TestHolder} methods in {@link BotGameTests}.
 */
// 26.2's @EventBusSubscriber has no bus() member any more; it infers the bus from the
// event type, and FMLConstructModEvent is a mod-bus event.
@EventBusSubscriber(modid = TerminatorPlus.MOD_ID)
public final class TplusTestFramework {

    private static MutableTestFramework framework;

    private TplusTestFramework() {
    }

    public static MutableTestFramework framework() {
        return framework;
    }

    @SubscribeEvent
    static void onConstruct(FMLConstructModEvent event) {
        ModContainer container = ModList.get()
                .getModContainerById(TerminatorPlus.MOD_ID)
                .orElseThrow(() -> new IllegalStateException("tplus mod container is missing"));

        framework = FrameworkConfiguration
                .builder(Identifier.fromNamespaceAndPath(TerminatorPlus.MOD_ID, "tests"))
                .build()
                .create();

        framework.init(container.getEventBus(), container);

        TerminatorPlus.LOGGER.info("TerminatorPlus GameTest framework initialised");
    }
}
