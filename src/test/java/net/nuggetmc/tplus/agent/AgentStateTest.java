package net.nuggetmc.tplus.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pure. Only the crack-list key needs testing — the other eleven collections are plain
 * fields and a test that asserts a HashMap behaves like a HashMap is noise.
 */
class AgentStateTest {

    private static ResourceKey<Level> dim(String path) {
        return ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("test", path));
    }

    @Test
    void aCrackKeyIsEqualForTheSamePositionInTheSameLevel() {
        assertEquals(new AgentState.BlockRef(Level.OVERWORLD, new BlockPos(1, 2, 3)),
                new AgentState.BlockRef(Level.OVERWORLD, new BlockPos(1, 2, 3)));
    }

    @Test
    void aCrackKeyDistinguishesLevels() {
        // Upstream keyed crackList on a Bukkit Block, whose equals() compares the world as
        // well as the coordinates. Keying on BlockPos alone would make two bots mining the
        // same coordinates in the Overworld and the Nether share one crack progress counter
        // and cancel each other's animation.
        assertNotEquals(new AgentState.BlockRef(Level.OVERWORLD, new BlockPos(1, 2, 3)),
                new AgentState.BlockRef(Level.NETHER, new BlockPos(1, 2, 3)));
    }

    @Test
    void aCrackKeyDistinguishesPositions() {
        assertNotEquals(new AgentState.BlockRef(dim("a"), new BlockPos(1, 2, 3)),
                new AgentState.BlockRef(dim("a"), new BlockPos(1, 2, 4)));
    }
}
