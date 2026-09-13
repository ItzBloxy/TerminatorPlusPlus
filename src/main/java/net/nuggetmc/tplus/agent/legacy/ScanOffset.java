package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * A direction and height a bot can attack a block at, relative to itself.
 *
 * <p>Ported from {@code LegacyLevel}. The name changes because "level" means a world in vanilla;
 * these are scan offsets, which is what {@code SurroundingScan} uses them for.
 *
 * <p>The constants encode two things at once: where the block is, and what pitch the bot should
 * hold while breaking it. {@code Mining.preBreak} reads the {@code isSideDown}/{@code isSideUp}
 * groups to pick 69 or -53 degrees.
 */
public enum ScanOffset {
    ABOVE(0, 2, 0),
    BELOW(0, -1, 0),
    AT(0, 1, 0),
    AT_D(0, 0, 0),
    NORTH_U(0, 2, -1),
    SOUTH_U(0, 2, 1),
    EAST_U(1, 2, 0),
    WEST_U(-1, 2, 0),
    NORTH(0, 1, -1),
    SOUTH(0, 1, 1),
    EAST(1, 1, 0),
    WEST(-1, 1, 0),
    NORTH_D(0, 0, -1),
    SOUTH_D(0, 0, 1),
    EAST_D(1, 0, 0),
    WEST_D(-1, 0, 0),
    NORTHWEST_D(-1, 0, -1),
    SOUTHWEST_D(-1, 0, 1),
    NORTHEAST_D(1, 0, -1),
    SOUTHEAST_D(1, 0, 1),
    NORTH_D_2(0, -1, -1),
    SOUTH_D_2(0, -1, 1),
    EAST_D_2(1, -1, 0),
    WEST_D_2(-1, -1, 0);

    /** The four that are not directional. {@link #isSide()} is the negation of this. */
    private static final Set<ScanOffset> NON_SIDE = Set.of(ABOVE, BELOW, AT, AT_D);

    private static final Set<ScanOffset> SIDE_AT = Set.of(NORTH, SOUTH, EAST, WEST);
    private static final Set<ScanOffset> SIDE_UP = Set.of(NORTH_U, SOUTH_U, EAST_U, WEST_U);
    private static final Set<ScanOffset> SIDE_DOWN = Set.of(NORTH_D, SOUTH_D, EAST_D, WEST_D);
    private static final Set<ScanOffset> SIDE_DOWN_2 = Set.of(NORTH_D_2, SOUTH_D_2, EAST_D_2, WEST_D_2);

    private final Vec3i offset;

    ScanOffset(int x, int y, int z) {
        this.offset = new Vec3i(x, y, z);
    }

    /**
     * {@code pos} displaced by this offset.
     *
     * <p>Upstream's {@code offset(Location)} mutated its argument, because Bukkit's
     * {@code Location.add} does. {@code BlockPos} is immutable, so this returns a new position
     * and no caller has to clone defensively — which several upstream callers forgot to do.
     */
    public BlockPos apply(BlockPos pos) {
        return pos.offset(offset);
    }

    /** True for every constant except the four in {@link #NON_SIDE}. */
    public boolean isSide() {
        return !NON_SIDE.contains(this);
    }

    public boolean isSideAt() {
        return SIDE_AT.contains(this);
    }

    public boolean isSideUp() {
        return SIDE_UP.contains(this);
    }

    public boolean isSideDown() {
        return SIDE_DOWN.contains(this);
    }

    public boolean isSideDown2() {
        return SIDE_DOWN_2.contains(this);
    }

    /** One rung up the height ladder D_2 -&gt; D -&gt; at -&gt; U, or null at the top. */
    public @Nullable ScanOffset sideUp() {
        return switch (this) {
            case NORTH -> NORTH_U;
            case SOUTH -> SOUTH_U;
            case EAST -> EAST_U;
            case WEST -> WEST_U;
            case NORTH_D -> NORTH;
            case SOUTH_D -> SOUTH;
            case EAST_D -> EAST;
            case WEST_D -> WEST;
            case NORTH_D_2 -> NORTH_D;
            case SOUTH_D_2 -> SOUTH_D;
            case EAST_D_2 -> EAST_D;
            case WEST_D_2 -> WEST_D;
            default -> null;
        };
    }

    /** One rung down the height ladder, or null at the bottom. */
    public @Nullable ScanOffset sideDown() {
        return switch (this) {
            case NORTH_U -> NORTH;
            case SOUTH_U -> SOUTH;
            case EAST_U -> EAST;
            case WEST_U -> WEST;
            case NORTH -> NORTH_D;
            case SOUTH -> SOUTH_D;
            case EAST -> EAST_D;
            case WEST -> WEST_D;
            case NORTH_D -> NORTH_D_2;
            case SOUTH_D -> SOUTH_D_2;
            case EAST_D -> EAST_D_2;
            case WEST_D -> WEST_D_2;
            default -> null;
        };
    }

    /** The constant matching the displacement from {@code start} to {@code end}, or null. */
    public static @Nullable ScanOffset getOffset(BlockPos start, BlockPos end) {
        int diffX = end.getX() - start.getX();
        int diffY = end.getY() - start.getY();
        int diffZ = end.getZ() - start.getZ();

        for (ScanOffset offset : values()) {
            if (offset.offset.getX() == diffX && offset.offset.getY() == diffY
                    && offset.offset.getZ() == diffZ) {
                return offset;
            }
        }

        return null;
    }

    /**
     * A mutable holder, ported from {@code LegacyLevel.LevelWrapper}.
     *
     * <p>{@code Mining.blockBreakEffect} runs as a repeating task and can decide part-way
     * through a break that it is attacking a different block — a bot suspended over lava
     * retargets up or down. The wrapper is how that decision survives between ticks of the
     * same task.
     */
    public static final class Wrapper {

        private @Nullable ScanOffset offset;

        public Wrapper(@Nullable ScanOffset offset) {
            this.offset = offset;
        }

        public @Nullable ScanOffset get() {
            return offset;
        }

        public void set(@Nullable ScanOffset offset) {
            this.offset = offset;
        }
    }
}
