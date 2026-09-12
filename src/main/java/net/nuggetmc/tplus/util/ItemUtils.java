package net.nuggetmc.tplus.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;

import static java.util.Map.entry;

/**
 * The 1.8 attack-damage table, ported verbatim from {@code api/utils/ItemUtils}.
 *
 * <p>Upstream switched on Bukkit's {@code Material} enum. Vanilla {@code Item} is not an enum,
 * so the switch becomes a lookup table; the grouping and the values are unchanged, including
 * the {@code 0.25} fallback for anything unlisted.
 *
 * <p>This deliberately ignores the item's real {@code ATTACK_DAMAGE} attribute. Bots are meant
 * to hit like a 1.8 player, and that is the behaviour being preserved.
 */
public final class ItemUtils {

    private static final double FIST = 0.25;

    private static final Map<Item, Double> LEGACY_DAMAGE = Map.ofEntries(
            entry(Items.WOODEN_SHOVEL, 1.0),
            entry(Items.GOLDEN_SHOVEL, 1.0),
            entry(Items.WOODEN_HOE, 1.0),
            entry(Items.GOLDEN_HOE, 1.0),
            entry(Items.STONE_HOE, 1.0),
            entry(Items.IRON_HOE, 1.0),
            entry(Items.DIAMOND_HOE, 1.0),
            entry(Items.NETHERITE_HOE, 1.0),

            entry(Items.WOODEN_PICKAXE, 2.0),
            entry(Items.GOLDEN_PICKAXE, 2.0),
            entry(Items.STONE_SHOVEL, 2.0),

            entry(Items.WOODEN_AXE, 3.0),
            entry(Items.GOLDEN_AXE, 3.0),
            entry(Items.STONE_PICKAXE, 3.0),
            entry(Items.IRON_SHOVEL, 3.0),

            entry(Items.WOODEN_SWORD, 4.0),
            entry(Items.GOLDEN_SWORD, 4.0),
            entry(Items.STONE_AXE, 4.0),
            entry(Items.IRON_PICKAXE, 4.0),
            entry(Items.DIAMOND_SHOVEL, 4.0),

            entry(Items.STONE_SWORD, 5.0),
            entry(Items.IRON_AXE, 5.0),
            entry(Items.DIAMOND_PICKAXE, 5.0),
            entry(Items.NETHERITE_SHOVEL, 5.0),

            entry(Items.IRON_SWORD, 6.0),
            entry(Items.DIAMOND_AXE, 6.0),
            entry(Items.NETHERITE_PICKAXE, 6.0),

            entry(Items.DIAMOND_SWORD, 7.0),
            entry(Items.NETHERITE_AXE, 7.0),

            entry(Items.NETHERITE_SWORD, 8.0));

    private ItemUtils() {
    }

    public static double getLegacyAttackDamage(ItemStack stack) {
        return getLegacyAttackDamage(stack.getItem());
    }

    /**
     * The table lookup, split out from the {@link ItemStack} overload so it can be unit tested.
     *
     * <p>Constructing an {@code ItemStack} outside a running server throws
     * "Components not bound yet" — the constructor reads the item's default data components, and
     * those are bound during registry load rather than by {@code Bootstrap.bootStrap()}. Resolving
     * an {@code Item} constant needs no such thing, so the table is testable at the pure tier and
     * only the one-line delegation above needs a server.
     */
    public static double getLegacyAttackDamage(Item item) {
        return LEGACY_DAMAGE.getOrDefault(item, FIST);
    }
}
