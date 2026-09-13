package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure: an enum of integer offsets. */
class ScanOffsetTest {

    @Test
    void offsetsAppliedToAPositionMatchTheirDeclaration() {
        BlockPos origin = new BlockPos(10, 64, 20);

        assertEquals(new BlockPos(10, 66, 20), ScanOffset.ABOVE.apply(origin));
        assertEquals(new BlockPos(10, 63, 20), ScanOffset.BELOW.apply(origin));
        assertEquals(new BlockPos(10, 65, 20), ScanOffset.AT.apply(origin));
        assertEquals(new BlockPos(10, 64, 20), ScanOffset.AT_D.apply(origin));
        assertEquals(new BlockPos(10, 65, 19), ScanOffset.NORTH.apply(origin));
        assertEquals(new BlockPos(11, 63, 20), ScanOffset.EAST_D_2.apply(origin));
    }

    @Test
    void applyDoesNotMutateTheInput() {
        // Upstream's offset(Location) called loc.add(...), which mutates a Bukkit Location in
        // place. Several callers relied on the mutation and several were bugs because of it;
        // BlockPos is immutable, so this pins the change as deliberate.
        BlockPos origin = new BlockPos(0, 0, 0);
        ScanOffset.ABOVE.apply(origin);

        assertEquals(new BlockPos(0, 0, 0), origin);
    }

    @Test
    void getOffsetFindsTheConstantForADisplacement() {
        assertEquals(ScanOffset.NORTH,
                ScanOffset.getOffset(new BlockPos(0, 0, 0), new BlockPos(0, 1, -1)));
        assertNull(ScanOffset.getOffset(new BlockPos(0, 0, 0), new BlockPos(7, 7, 7)));
    }

    @Test
    void theFourNonSideConstantsAreNotSides() {
        // isSide is the negation of a four-element set, so every constant added to the enum is
        // a "side" by default. That is upstream's definition and it is easy to get wrong.
        assertFalse(ScanOffset.ABOVE.isSide());
        assertFalse(ScanOffset.BELOW.isSide());
        assertFalse(ScanOffset.AT.isSide());
        assertFalse(ScanOffset.AT_D.isSide());

        assertTrue(ScanOffset.NORTH.isSide());
        assertTrue(ScanOffset.NORTHWEST_D.isSide());
    }

    @Test
    void theFourSideGroupsAreExactlyTheCardinalsAtEachHeight() {
        assertTrue(ScanOffset.NORTH.isSideAt());
        assertTrue(ScanOffset.NORTH_U.isSideUp());
        assertTrue(ScanOffset.NORTH_D.isSideDown());
        assertTrue(ScanOffset.NORTH_D_2.isSideDown2());

        // The diagonals belong to none of the four groups, so the lava-escape logic in
        // blockBreakEffect never re-pitches for them.
        assertFalse(ScanOffset.NORTHWEST_D.isSideAt());
        assertFalse(ScanOffset.NORTHWEST_D.isSideUp());
        assertFalse(ScanOffset.NORTHWEST_D.isSideDown());
        assertFalse(ScanOffset.NORTHWEST_D.isSideDown2());
    }

    @Test
    void sideUpAndSideDownWalkTheHeightLadder() {
        // The ladder is D_2 -> D -> at -> U. blockBreakEffect walks it when a bot standing over
        // lava has to retarget mid-break.
        assertEquals(ScanOffset.NORTH_D, ScanOffset.NORTH_D_2.sideUp());
        assertEquals(ScanOffset.NORTH, ScanOffset.NORTH_D.sideUp());
        assertEquals(ScanOffset.NORTH_U, ScanOffset.NORTH.sideUp());
        assertNull(ScanOffset.NORTH_U.sideUp(), "the top of the ladder has no rung above");

        assertEquals(ScanOffset.NORTH, ScanOffset.NORTH_U.sideDown());
        assertEquals(ScanOffset.NORTH_D, ScanOffset.NORTH.sideDown());
        assertEquals(ScanOffset.NORTH_D_2, ScanOffset.NORTH_D.sideDown());
        assertNull(ScanOffset.NORTH_D_2.sideDown(), "the bottom of the ladder has no rung below");
    }

    @Test
    void theLadderIsSymmetricForEveryDirection() {
        // Upstream wrote sideUp and sideDown as two 12-case switches. This is the invariant
        // that makes them a pair, and it catches a single transposed case.
        for (ScanOffset offset : ScanOffset.values()) {
            ScanOffset up = offset.sideUp();

            if (up != null) {
                assertEquals(offset, up.sideDown(), offset + ".sideUp().sideDown() must round-trip");
            }
        }
    }

    @Test
    void everyLadderStepChangesOnlyTheHeight() {
        // A transposed case that happened to round-trip would still pass the test above — for
        // instance if NORTH.sideUp() returned SOUTH_U and SOUTH_U.sideDown() returned NORTH.
        // A rung must keep its horizontal displacement and change only Y.
        for (ScanOffset offset : ScanOffset.values()) {
            ScanOffset up = offset.sideUp();

            if (up == null) {
                continue;
            }

            BlockPos from = offset.apply(BlockPos.ZERO);
            BlockPos to = up.apply(BlockPos.ZERO);

            assertEquals(from.getX(), to.getX(), offset + ".sideUp() changed X");
            assertEquals(from.getZ(), to.getZ(), offset + ".sideUp() changed Z");
            assertEquals(from.getY() + 1, to.getY(), offset + ".sideUp() must be one block up");
        }
    }

    @Test
    void theWrapperIsMutable() {
        // Mining mutates the offset mid-break when a bot over lava changes what it is
        // attacking, and the wrapper is how that change gets back to the caller.
        ScanOffset.Wrapper wrapper = new ScanOffset.Wrapper(ScanOffset.NORTH);
        wrapper.set(ScanOffset.NORTH_D);

        assertEquals(ScanOffset.NORTH_D, wrapper.get());
    }

    @Test
    void theEnumHasUpstreamsTwentyFourConstants() {
        assertEquals(24, ScanOffset.values().length);
    }
}
