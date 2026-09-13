package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.world.entity.LivingEntity;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;

/**
 * Odds and ends of bot behaviour that are neither navigation nor mining.
 *
 * <p><b>Partial.</b> {@code miscellaneousChecks} (fire, lava, magma, boats) and {@code onBoat}
 * land in task 22. {@code resetHand} is here because {@code tickBot} cannot run without it.
 */
public final class BotBehaviors {

    private final AgentState state;
    private final Navigation navigation;

    public BotBehaviors(AgentState state, Navigation navigation) {
        this.state = state;
        this.navigation = navigation;
    }

    /**
     * Faces the target, stops any swing animation, and empties the hand.
     *
     * <p>Ported from {@code resetHand}. Three upstream details are preserved: the {@code noFace}
     * check exists purely as an optimisation (its comment reads "LESSLAG if there is no if
     * statement here"), the early return for a bot on boat cooldown leaves the boat in its hand,
     * and {@code setItem(null)} means "restore the default item", not "empty".
     */
    public void resetHand(Bot bot, LivingEntity target) {
        if (!state.noFace.contains(bot)) {
            bot.faceLocation(target.position());
        }

        navigation.cancelMiningAnim(bot);

        if (state.boatCooldown.contains(bot)) {
            return;
        }

        bot.setItem(null);
    }
}
