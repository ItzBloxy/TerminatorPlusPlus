package net.nuggetmc.tplus.bot;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure, but only just, and for the same reason {@code ItemUtilsTest} is: resolving an
 * {@code Item} constant works outside a server, while {@code new ItemStack(item)} throws
 * "Components not bound yet". Nothing here builds a stack.
 */
class EquipmentTierTest {

    @Test
    void namesRoundTripAndAreCaseInsensitive() {
        for (EquipmentTier tier : EquipmentTier.values()) {
            assertSame(tier, EquipmentTier.byName(tier.id()));
            assertSame(tier, EquipmentTier.byName(tier.id().toUpperCase(Locale.ROOT)));
        }
    }

    @Test
    void anUnknownNameIsNull() {
        assertNull(EquipmentTier.byName("mithril"));
        assertNull(EquipmentTier.byName(""));
    }

    @Test
    void noneIsAcceptedInBothSlotsAndEquipsNothing() {
        // `none` is the filler the create chain uses to skip a slot, so it has to parse in
        // both positions while equipping nothing. Every other tier that parses in a slot has
        // items for it; this one is the exception, which is why acceptance is not just
        // "the array is non-empty".
        assertTrue(EquipmentTier.NONE.acceptsAsArmor());
        assertTrue(EquipmentTier.NONE.acceptsAsTools());

        for (int i = 0; i < EquipmentTier.ARMOR_SLOTS.length; i++) {
            assertNull(EquipmentTier.NONE.armorPiece(i));
        }

        assertEquals(List.of(), EquipmentTier.NONE.tools());
    }

    @Test
    void vanillaTiersAreNotSymmetricAndTheEnumSaysSo() {
        assertTrue(EquipmentTier.LEATHER.acceptsAsArmor());
        assertFalse(EquipmentTier.LEATHER.acceptsAsTools());

        assertTrue(EquipmentTier.CHAIN.acceptsAsArmor());
        assertFalse(EquipmentTier.CHAIN.acceptsAsTools());

        assertFalse(EquipmentTier.WOOD.acceptsAsArmor());
        assertTrue(EquipmentTier.WOOD.acceptsAsTools());

        assertFalse(EquipmentTier.STONE.acceptsAsArmor());
        assertTrue(EquipmentTier.STONE.acceptsAsTools());

        // Copper equipment is new in 26.2 and is complete on both halves.
        assertTrue(EquipmentTier.COPPER.acceptsAsArmor());
        assertTrue(EquipmentTier.COPPER.acceptsAsTools());
    }

    @Test
    void everyPopulatedTierIsCompleteAndHasNoHoles() {
        for (EquipmentTier tier : EquipmentTier.values()) {
            if (tier == EquipmentTier.NONE) {
                continue;
            }

            if (tier.acceptsAsArmor()) {
                for (int i = 0; i < EquipmentTier.ARMOR_SLOTS.length; i++) {
                    assertNotNull(tier.armorPiece(i), tier.id() + " armour slot " + i);
                }
            }

            if (tier.acceptsAsTools()) {
                assertEquals(3, tier.tools().size(), tier.id() + " tools");

                // allMatch, not contains(null): tools() hands back a List.of, and
                // ImmutableCollections.contains throws NPE on a null probe rather than
                // returning false. The list construction would have rejected a null element
                // anyway, so this is belt and braces -- but it must not be the thing that
                // throws.
                assertTrue(tier.tools().stream().allMatch(Objects::nonNull),
                        tier.id() + " tools");
            }
        }
    }

    @Test
    void armorPiecesAreInSlotOrder() {
        // armorPiece(i) has to line up with ARMOR_SLOTS[i] or a bot wears its boots on its
        // head, and nothing else in the codebase would notice.
        assertSame(Items.DIAMOND_BOOTS, EquipmentTier.DIAMOND.armorPiece(0));
        assertSame(Items.DIAMOND_LEGGINGS, EquipmentTier.DIAMOND.armorPiece(1));
        assertSame(Items.DIAMOND_CHESTPLATE, EquipmentTier.DIAMOND.armorPiece(2));
        assertSame(Items.DIAMOND_HELMET, EquipmentTier.DIAMOND.armorPiece(3));

        assertEquals(EquipmentSlot.FEET, EquipmentTier.ARMOR_SLOTS[0]);
        assertEquals(EquipmentSlot.LEGS, EquipmentTier.ARMOR_SLOTS[1]);
        assertEquals(EquipmentSlot.CHEST, EquipmentTier.ARMOR_SLOTS[2]);
        assertEquals(EquipmentSlot.HEAD, EquipmentTier.ARMOR_SLOTS[3]);
    }

    @Test
    void toolsArePickaxeAxeShovelInThatOrder() {
        // Mining.optimalTool iterates this list and keeps the fastest, so order does not
        // change the outcome -- but IRON is upstream's LegacyItems set verbatim and this is
        // what pins it.
        assertEquals(List.of(Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL),
                EquipmentTier.IRON.tools());
    }

    @Test
    void theToolsSlotFloorsAtWood() {
        // `none` parses in the tools slot because it is the create chain's filler, but there is
        // no bare-handed tier: break progress is the tool's destroy speed and an empty hand
        // scores 1.0 against everything.
        assertSame(EquipmentTier.WOOD, EquipmentTier.NONE.asToolTier());

        for (EquipmentTier tier : EquipmentTier.values()) {
            if (tier != EquipmentTier.NONE) {
                assertSame(tier, tier.asToolTier(), tier.id() + " must be left alone");
            }

            if (tier.acceptsAsTools()) {
                assertFalse(tier.asToolTier().tools().isEmpty(),
                        tier.id() + " parses in the tools slot and must yield real tools");
            }
        }
    }

    @Test
    void theSuggestionListsAgreeWithWhatParses() {
        // The command's completions and its rejection message both read these two lists, so a
        // tier that tab-completes but then fails to parse is impossible by construction.
        for (EquipmentTier tier : EquipmentTier.values()) {
            assertEquals(tier.acceptsAsArmor(), EquipmentTier.armorTiers().contains(tier.id()),
                    tier.id() + " in armorTiers()");
            assertEquals(tier.acceptsAsTools(), EquipmentTier.toolTiers().contains(tier.id()),
                    tier.id() + " in toolTiers()");
        }
    }
}
