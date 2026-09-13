package net.nuggetmc.tplus.bot;

import net.minecraft.world.entity.EntityType;

import java.util.Set;
import java.util.UUID;

/**
 * What the {@code ENTITY} goal chases.
 *
 * <p>New in this port. Upstream could name one player ({@code targetPlayer}) or a list of mob
 * <i>types</i> ({@code CUSTOM_MOB_LIST}), and had no way to say "that entity".
 *
 * <p>Two halves, and exactly one is ever populated, because the command that sets it reads
 * {@code generic|specific}:
 *
 * <ul>
 *   <li>{@code types} — <b>live</b>. Anything of these types, including one that spawns later.
 *   <li>{@code ids} — <b>fixed</b>. These entities and no others, however many are still alive.
 * </ul>
 *
 * <p>Immutable, so every bot can hold the same reference and the per-bot form costs one field.
 *
 * <p>This is deliberately <i>not</i> {@code Targeting.CUSTOM_MOB_LIST}, which it resembles. That
 * list only matches when {@code customListMode == CUSTOM}, so an operator who had set
 * {@code moblisttype hostile} would watch this command succeed and do nothing; and three other
 * goals read it, so writing to it here would change them invisibly.
 */
public record EnemyTarget(Set<EntityType<?>> types, Set<UUID> ids, String label) {

    /** Nothing set. What a bot carries until {@code /tplus enemytarget} names something. */
    public static final EnemyTarget NONE = new EnemyTarget(Set.of(), Set.of(), "nothing");

    public EnemyTarget {
        types = Set.copyOf(types);
        ids = Set.copyOf(ids);
    }

    /** Live, by type. {@code label} is what {@code /tplus enemytarget} prints back. */
    public static EnemyTarget ofTypes(Set<EntityType<?>> types, String label) {
        return new EnemyTarget(types, Set.of(), label);
    }

    /** Fixed, by entity. */
    public static EnemyTarget ofEntities(Set<UUID> ids, String label) {
        return new EnemyTarget(Set.of(), ids, label);
    }

    public boolean isEmpty() {
        return types.isEmpty() && ids.isEmpty();
    }

    /**
     * Whether an entity is a target.
     *
     * <p>Takes the two fields rather than the {@code Entity} on purpose: an Entity cannot be
     * constructed without a level, and taking a type and an id keeps this rule testable without
     * a world. {@code Targeting.weightedRegionDist} is parameterised for the same reason.
     */
    public boolean matches(EntityType<?> type, UUID id) {
        return types.contains(type) || ids.contains(id);
    }
}
