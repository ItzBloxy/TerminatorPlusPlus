package net.nuggetmc.tplus.util;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure, but only just: resolving an {@code Item} constant works outside a server, while
 * constructing an {@code ItemStack} from one does not.
 *
 * <p>{@code new ItemStack(Items.X)} throws "Components not bound yet" here — the constructor
 * reads the item's default data components, and those are bound during registry load, not by
 * {@code Bootstrap.bootStrap()}. That is why {@link ItemUtils} exposes the table lookup by
 * {@code Item}: the thirty entries stay at this tier, and only the one-line {@code ItemStack}
 * delegation needs a running server, which {@code BotActionTests} covers.
 */
class ItemUtilsTest {

    @Test
    void theDamageTableIsThe18Table() {
        // Upstream's whole point: a bot hits for 1.8 damage values regardless of what the
        // current game says a diamond sword does. These five pin the ends and the middle.
        assertEquals(8.0, ItemUtils.getLegacyAttackDamage(Items.NETHERITE_SWORD));
        assertEquals(7.0, ItemUtils.getLegacyAttackDamage(Items.DIAMOND_SWORD));
        assertEquals(4.0, ItemUtils.getLegacyAttackDamage(Items.WOODEN_SWORD));
        assertEquals(3.0, ItemUtils.getLegacyAttackDamage(Items.IRON_SHOVEL));
        assertEquals(1.0, ItemUtils.getLegacyAttackDamage(Items.NETHERITE_HOE));
    }

    @Test
    void anUnlistedItemDealsTheFistDamage() {
        // Upstream's `default: return 0.25`. A bot with nothing in hand still does something,
        // and this is the value the agent relies on for a bare-handed bot.
        assertEquals(0.25, ItemUtils.getLegacyAttackDamage(Items.COBBLESTONE));
        assertEquals(0.25, ItemUtils.getLegacyAttackDamage(Items.DIAMOND_HELMET));
    }

    @Test
    void anEmptyStackDealsTheFistDamage() {
        // The Paper build initialised defaultItem to ItemStack(Material.AIR) and passed it
        // straight in. The vanilla equivalent is ItemStack.EMPTY, whose item is Items.AIR —
        // unlisted, so 0.25. Also the one case that exercises the ItemStack overload here:
        // EMPTY is a singleton and needs no component binding.
        assertEquals(0.25, ItemUtils.getLegacyAttackDamage(ItemStack.EMPTY));
    }

    @Test
    void aStoneAxeAndAnIronPickaxeAgree() {
        // Upstream grouped these two on the same case label. Easy to lose when a switch on
        // an enum becomes a map of thirty entries, and nothing else would notice.
        assertEquals(ItemUtils.getLegacyAttackDamage(Items.STONE_AXE),
                ItemUtils.getLegacyAttackDamage(Items.IRON_PICKAXE));
        assertEquals(4.0, ItemUtils.getLegacyAttackDamage(Items.STONE_AXE));
    }

    @Test
    void everyToolTierIsDistinctWithinAFamily() {
        // The table is thirty hand-copied entries; a transposed pair would be invisible above.
        // Swords ascend wood/gold 4, stone 5, iron 6, diamond 7, netherite 8.
        assertEquals(4.0, ItemUtils.getLegacyAttackDamage(Items.GOLDEN_SWORD));
        assertEquals(5.0, ItemUtils.getLegacyAttackDamage(Items.STONE_SWORD));
        assertEquals(6.0, ItemUtils.getLegacyAttackDamage(Items.IRON_SWORD));
        assertEquals(7.0, ItemUtils.getLegacyAttackDamage(Items.DIAMOND_SWORD));
        assertEquals(8.0, ItemUtils.getLegacyAttackDamage(Items.NETHERITE_SWORD));
    }
}
