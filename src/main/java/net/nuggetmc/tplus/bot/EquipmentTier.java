package net.nuggetmc.tplus.bot;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a bot wears and what it mines with.
 *
 * <p>New in this port. Upstream had one hardcoded armour set behind {@code /bot armor} and one
 * hardcoded iron tool set in {@code LegacyItems}; both become a tier here so that a bot can be
 * equipped at the moment it spawns rather than by a second command afterwards. {@link #IRON} is
 * the tool default, and it is {@code LegacyItems} verbatim, so a bot nobody configured mines
 * exactly as it always did.
 *
 * <p><b>Vanilla tiers are not symmetric.</b> Leather and chainmail have no tools; wood and stone
 * have no armour. An absent half is an empty array. {@link #NONE} has neither half and is
 * accepted in both slots anyway, because it is the filler the {@code /tplus create} chain uses to
 * skip a slot — so {@link #acceptsAsArmor()} is not simply "the array is non-empty".
 *
 * <p><b>{@code Item} constants, never {@code ItemStack}s.</b> Constructing a stack in a static
 * initialiser throws "Components not bound yet" — the constructor reads the item's default data
 * components, and those are bound during registry load. As a static field that breaks mod loading
 * outright. Stacks are built at the call site.
 */
public enum EquipmentTier {

    /** Accepted in both slots and equips nothing. The filler that skips a slot. */
    NONE(new Item[]{}, new Item[]{}),

    LEATHER(new Item[]{Items.LEATHER_BOOTS, Items.LEATHER_LEGGINGS,
                    Items.LEATHER_CHESTPLATE, Items.LEATHER_HELMET},
            new Item[]{}),

    CHAIN(new Item[]{Items.CHAINMAIL_BOOTS, Items.CHAINMAIL_LEGGINGS,
                    Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_HELMET},
            new Item[]{}),

    WOOD(new Item[]{},
            new Item[]{Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL}),

    STONE(new Item[]{},
            new Item[]{Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL}),

    COPPER(new Item[]{Items.COPPER_BOOTS, Items.COPPER_LEGGINGS,
                    Items.COPPER_CHESTPLATE, Items.COPPER_HELMET},
            new Item[]{Items.COPPER_PICKAXE, Items.COPPER_AXE, Items.COPPER_SHOVEL}),

    GOLD(new Item[]{Items.GOLDEN_BOOTS, Items.GOLDEN_LEGGINGS,
                    Items.GOLDEN_CHESTPLATE, Items.GOLDEN_HELMET},
            new Item[]{Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL}),

    IRON(new Item[]{Items.IRON_BOOTS, Items.IRON_LEGGINGS,
                    Items.IRON_CHESTPLATE, Items.IRON_HELMET},
            new Item[]{Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL}),

    DIAMOND(new Item[]{Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS,
                    Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET},
            new Item[]{Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL}),

    NETHERITE(new Item[]{Items.NETHERITE_BOOTS, Items.NETHERITE_LEGGINGS,
                    Items.NETHERITE_CHESTPLATE, Items.NETHERITE_HELMET},
            new Item[]{Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL});

    /** The slots {@link #armorPiece(int)} indexes, in order. */
    public static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

    private static final Map<String, EquipmentTier> BY_NAME = new HashMap<>();

    static {
        for (EquipmentTier tier : values()) {
            BY_NAME.put(tier.id(), tier);
        }
    }

    private final Item[] armor;
    private final Item[] tools;

    EquipmentTier(Item[] armor, Item[] tools) {
        this.armor = armor;
        this.tools = tools;
    }

    /** @return the tier with this name, case-insensitively, or null. */
    public static @Nullable EquipmentTier byName(String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Every tier nameable in an armour slot.
     *
     * <p>The command's suggestions and its rejection message both read this, so a tier that
     * tab-completes but then fails to parse is impossible by construction.
     */
    public static List<String> armorTiers() {
        return Arrays.stream(values()).filter(EquipmentTier::acceptsAsArmor)
                .map(EquipmentTier::id).toList();
    }

    /** Every tier nameable in a tools slot. */
    public static List<String> toolTiers() {
        return Arrays.stream(values()).filter(EquipmentTier::acceptsAsTools)
                .map(EquipmentTier::id).toList();
    }

    /** The lower-case name an operator types. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean acceptsAsArmor() {
        return this == NONE || armor.length > 0;
    }

    public boolean acceptsAsTools() {
        return this == NONE || tools.length > 0;
    }

    /**
     * The piece for {@code ARMOR_SLOTS[index]}, or null for a bare slot.
     *
     * <p>Null past the end of the array rather than an exception, so {@link #NONE} — whose array
     * is empty — strips all four slots through the same loop that fills them.
     */
    public @Nullable Item armorPiece(int index) {
        return index < armor.length ? armor[index] : null;
    }

    /** Pickaxe, axe, shovel. Empty for a tier with no tools and for {@link #NONE}. */
    public List<Item> tools() {
        return List.of(tools);
    }

    /**
     * This tier when it is used for tools: {@link #NONE} becomes {@link #WOOD}.
     *
     * <p>{@code none} has to parse in the tools slot because it is the {@code /tplus create}
     * chain's filler word, but there is no bare-handed tier — break progress is the held tool's
     * destroy speed, and an empty hand scores 1.0 against everything, which is 120 ticks a block.
     *
     * <p>One definition, called by both {@code Bot.setToolTier} and the commands' feedback, so
     * the clamp and the message an operator reads cannot disagree.
     */
    public EquipmentTier asToolTier() {
        return this == NONE ? WOOD : this;
    }

    /**
     * Puts this tier's four pieces on a bot, clearing any slot the tier has nothing for.
     *
     * <p>{@link #NONE} strips all four through this same loop: {@link #armorPiece(int)} returns
     * null past the end of its empty array.
     *
     * <p>Upstream wrote the Bukkit inventory <i>and</i> sent the equipment packets, with the
     * comment "packet sending to ensure"; {@code Bot.setItem(stack, slot)} already does both, so
     * one call per slot is enough.
     *
     * <p>Lives here rather than in {@code BotCommands} so the index-to-slot pairing sits beside
     * the table it indexes, and so a GameTest can reach it.
     */
    public void equipArmor(Bot bot) {
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            Item piece = armorPiece(i);

            bot.setItem(piece == null ? ItemStack.EMPTY : new ItemStack(piece), ARMOR_SLOTS[i]);
        }
    }
}
