package net.nuggetmc.tplus.bot;

import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure. {@code matches} takes a type and a UUID rather than an {@code Entity} precisely so that
 * the whole targeting rule can be tested here, in milliseconds, instead of in the GameTest tier
 * where it would need a world and a server.
 */
class EnemyTargetTest {

    private static final UUID ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void nothingSetMatchesNothing() {
        assertTrue(EnemyTarget.NONE.isEmpty());
        assertFalse(EnemyTarget.NONE.matches(EntityTypes.ZOMBIE, ONE));
        assertEquals("nothing", EnemyTarget.NONE.label());
    }

    @Test
    void aGenericTargetMatchesByTypeWhateverTheEntityIs() {
        // The point of `generic`: a zombie that does not exist yet has a UUID nobody can know,
        // and it still matches.
        EnemyTarget target = EnemyTarget.ofTypes(Set.of(EntityTypes.ZOMBIE), "minecraft:zombie");

        assertTrue(target.matches(EntityTypes.ZOMBIE, ONE));
        assertTrue(target.matches(EntityTypes.ZOMBIE, TWO));
        assertFalse(target.matches(EntityTypes.COW, ONE));
        assertFalse(target.isEmpty());
    }

    @Test
    void aSpecificTargetMatchesByIdWhateverTheTypeIs() {
        EnemyTarget target = EnemyTarget.ofEntities(Set.of(ONE), "1 entities (zombie)");

        assertTrue(target.matches(EntityTypes.ZOMBIE, ONE));

        // The type is not consulted at all for an id match, which is what lets `specific` pin
        // any entity without naming its type.
        assertTrue(target.matches(EntityTypes.COW, ONE));
        assertFalse(target.matches(EntityTypes.ZOMBIE, TWO));
    }

    @Test
    void aGenericTargetHoldsSeveralTypes() {
        // How a tag arrives: #minecraft:raiders expands to its members before it is stored.
        EnemyTarget target = EnemyTarget.ofTypes(
                Set.of(EntityTypes.PILLAGER, EntityTypes.VINDICATOR), "#minecraft:raiders");

        assertTrue(target.matches(EntityTypes.PILLAGER, ONE));
        assertTrue(target.matches(EntityTypes.VINDICATOR, ONE));
        assertFalse(target.matches(EntityTypes.ZOMBIE, ONE));
    }

    @Test
    void theSetsAreCopiedSoACallersMutationCannotReachABot() {
        // Every bot holds the same EnemyTarget reference, so a mutable set leaking in would let
        // one command's local variable silently re-aim every bot in the world.
        Set<UUID> ids = new HashSet<>(Set.of(ONE));
        EnemyTarget target = EnemyTarget.ofEntities(ids, "1 entities (zombie)");

        ids.add(TWO);

        assertTrue(target.matches(EntityTypes.ZOMBIE, ONE));
        assertFalse(target.matches(EntityTypes.ZOMBIE, TWO));
    }
}
