package net.nuggetmc.tplus.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.bot.Bot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every piece of mutable state {@code LegacyAgent} shares with its collaborators.
 *
 * <p>Upstream held all twelve of these as fields on the 1,600-line class, read and written from
 * every concern in it. Spec §4.3: they move here <b>as a group</b>, injected into each
 * collaborator, because deciding per-class ownership means guessing at sharing semantics and
 * guessing wrong changes behaviour silently. Narrowing ownership is a separate, deliberate change.
 *
 * <p>Two key-space notes. Upstream keyed some of these on {@code Terminator} and others on the
 * bot's Bukkit {@code LivingEntity} — {@code noFace} on the former, {@code noJump} on the latter.
 * Those are one-to-one, so both become {@code Bot} here and the two key spaces merge with no
 * observable difference. {@code Bot} is safe as a key for the reason Plan A recorded on
 * {@code BotRegistry.bots}: {@code Entity} equality is by entity id, ids come from a monotonic
 * counter, and they never change while an entity lives.
 *
 * <p>The exception is {@code crackList}, which upstream keyed on a Bukkit {@code Block} —
 * world <b>and</b> position. {@link BlockRef} preserves that; see the test for what a bare
 * {@code BlockPos} would break.
 */
public final class AgentState {

    /** A block identified by level and position, matching Bukkit {@code Block} equality. */
    public record BlockRef(ResourceKey<Level> level, BlockPos pos) {
    }

    /** Bots that must not be turned to face their target this tick. */
    public final Set<Bot> noFace = new HashSet<>();

    /** Bots that must not jump — set while mining the block underfoot. */
    public final Set<Bot> noJump = new HashSet<>();

    /** Bots moving at half speed, set during a clutch. */
    public final Set<Bot> slow = new HashSet<>();

    /**
     * Bots with a mining swing animation running, mapped to the scheduler task id.
     *
     * <p>Upstream mapped to the {@code BukkitRunnable} itself and cancelled it directly. Our
     * {@code TickScheduler} hands out ids, so the id is what is stored; cancellation is
     * {@code agent.cancel(id)}.
     */
    public final Map<Bot, Integer> miningAnim = new HashMap<>();

    /** Boats spawned to carry a bot over lava. */
    public final Set<Boat> boats = new HashSet<>();

    /** Each bot's position as of the last centring check, 20 ticks ago. */
    public final Map<Bot, Vec3> btList = new HashMap<>();

    /** Whether each bot has stayed in the same block column since the last check. */
    public final Map<Bot, Boolean> btCheck = new HashMap<>();

    /** Bots currently towering, mapped to where they started. */
    public final Map<Bot, Vec3> towerList = new HashMap<>();

    /** Bots that have just used a boat and must not spawn another yet. */
    public final Set<Bot> boatCooldown = new HashSet<>();

    /**
     * Blocks being mined, mapped to the break-animation id sent to clients.
     *
     * <p>The value is upstream's {@code random.nextInt(2000)} — an arbitrary id so that two
     * bots cracking two blocks do not overwrite each other's animation.
     */
    public final Map<BlockRef, Short> crackList = new HashMap<>();

    /** Mining task id to progress stage, 0 through 9. */
    public final Map<Integer, Byte> mining = new HashMap<>();

    /** Bots that took fall damage recently and must not be shoved downward again. */
    public final Set<Bot> fallDamageCooldown = new HashSet<>();

    /**
     * Forgets everything about {@code bot}. Called when a bot is removed.
     *
     * <p><b>Not upstream.</b> Upstream leaked an entry in nine collections per dead bot,
     * forever — the maps are keyed by entity and nothing ever removed them. For a plugin that
     * spawns a hundred bots and kills them repeatedly that is a slow leak of dead
     * {@code ServerPlayer} references, each holding an inventory and an advancement tracker.
     * It cannot change behaviour, because every lookup is keyed by a live bot.
     */
    public void forget(Bot bot) {
        noFace.remove(bot);
        noJump.remove(bot);
        slow.remove(bot);
        miningAnim.remove(bot);
        btList.remove(bot);
        btCheck.remove(bot);
        towerList.remove(bot);
        boatCooldown.remove(bot);
        fallDamageCooldown.remove(bot);
    }
}
