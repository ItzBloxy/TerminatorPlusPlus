# TerminatorPlus NeoForge Port — Plan D: Loadouts and Entity Targeting

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Arm a bot at the moment it spawns, and point bots at a named entity or entity type.

**Architecture:** One new enum (`EquipmentTier`) and one new record (`EnemyTarget`), both in
`net.nuggetmc.tplus.bot`, both pure data with no world dependency. Two new `Bot` fields read them.
`Mining` loses its hardcoded tool list, `Targeting` gains one goal branch, and `BotCommands` grows
the `create` chain plus two commands. No new packages, no new classes beyond those two.

**Tech Stack:** Java 25, Gradle 9.2.1, ModDevGradle 2.0.147, NeoForge 26.2.0.87, Minecraft 26.2,
Brigadier, JUnit 6.1.3, NeoForge `testframework` + GameTests.

---

**Spec:** `docs/superpowers/specs/2026-09-13-loadouts-and-entity-targeting-design.md`. These are
features upstream never had, so that spec stands in for `master` — there is nothing to diff against.

**Plans A, B and C:** complete. **Plan B's deviation register is the one this plan extends**
(`docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md`, currently numbered to 21). Do not
start a new list.

**Branch:** `neoforge-port`. The Paper 1.21.1 source is on `master`. Two of these tasks *do* touch
ported code — `Mining.TOOLS`, `TargetGoal`, `playertarget` — and for those the original is still the
reference for what you are changing away from:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/LegacyItems.java
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/EnumTargetGoal.java
```

**Verify every vanilla signature against the patched jar**, not a Paper jar:

```bash
J=build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar
unzip -p $J net/minecraft/commands/arguments/ResourceOrTagArgument.java | sed -n '48,60p'
```

Every signature quoted below was checked against it on 2026-09-13.

---

## Scope

**In:** armour, tools and a held item as arguments to `/tplus create`; a per-bot tool tier;
`/tplus tools`; `/tplus enemytarget generic|specific`; `TargetGoal.ENTITY`; both targeting commands
setting the goal.

**Out, unchanged from the port spec §4.4 and every prior plan:** the neural AI and `/tplus ai`,
`Debugger`, the public API module, narrowing `AgentState`, persistence of any kind.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| **Create** `src/main/java/net/nuggetmc/tplus/bot/EquipmentTier.java` | The tier table. Pure data: ten constants, two `Item[]` each, no behaviour beyond lookup | 1 |
| **Create** `src/test/java/net/nuggetmc/tplus/bot/EquipmentTierTest.java` | Lookup, the armour/tools partition, slot order | 1 |
| **Modify** `src/main/java/net/nuggetmc/tplus/bot/Bot.java` | Two fields: `toolTier`, `enemyTarget` | 2, 6 |
| **Modify** `src/main/java/net/nuggetmc/tplus/agent/legacy/Mining.java` | `TOOLS` deleted; `optimalTool` takes a tier | 2 |
| **Modify** `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java` | A bot mines with its own tier | 2 |
| **Modify** `src/main/java/net/nuggetmc/tplus/command/BotCommands.java` | `ARMOR_TIERS`/`ARMOR_SLOTS` deleted; `create` chain; `tools`; `enemytarget`; `playertarget` | 3, 4, 7 |
| **Create** `src/main/java/net/nuggetmc/tplus/bot/EnemyTarget.java` | What the ENTITY goal chases. Pure data: two sets and a label | 5 |
| **Create** `src/test/java/net/nuggetmc/tplus/bot/EnemyTargetTest.java` | The match rule, with no world | 5 |
| **Modify** `src/main/java/net/nuggetmc/tplus/agent/legacy/TargetGoal.java` | One new constant | 6 |
| **Modify** `src/main/java/net/nuggetmc/tplus/agent/legacy/Targeting.java` | One new `switch` branch | 6 |
| **Create** `src/gametest/java/net/nuggetmc/tplus/gametest/EnemyTargetTests.java` | The ENTITY goal in a world | 6 |

`BotCommands` is 886 lines and this plan adds two commands to it. Task 3 removes 20 lines of tier
tables on the way through, which is not a fix but does stop it getting worse; a real split is out of
scope and belongs in `docs/backlog.md` if it ever matters.

---

## Decisions

### 1. `none` means none, but *omitting* tools means iron

The chain's filler word and an absent argument are not the same thing, and they cannot be:

| | armour | tools |
|---|---|---|
| argument omitted | none — today's behaviour, a bot spawns bare | **iron** — today's behaviour, upstream's `LegacyItems` |
| argument is `none` | none | **nothing at all**, bare hands |

Making an omitted tools argument mean "no tools" would change what `/tplus create Hunter 5` does,
which is the one thing this feature must not do. So `create Hunter 5` gives iron tools and
`create Hunter 5 none none none` gives none, and that asymmetry is deliberate. Say it in the
command's feedback so an operator does not have to infer it.

### 2. The tool tier changes what a bot *holds*, not how fast it mines

Worth knowing before someone tests netherite and reports a bug. `Mining.blockBreakEffect` is a fixed
ladder of ten stages at two ticks each, so every block takes twenty ticks regardless of tool or
hardness — upstream's design, and untouched here. `optimalTool` only decides which stack goes in the
main hand, and `ItemUtils.getLegacyAttackDamage` scores `defaultItem`, not the held tool.

So a tier affects appearance and nothing else today. That is fine and it is still worth having; it
is not fine to let it be discovered as a disappointment.

### 3. `EnemyTarget.matches` takes a type and a UUID, not an `Entity`

```java
public boolean matches(EntityType<?> type, UUID id)     // not matches(Entity)
```

An `Entity` cannot be constructed without a level, so `matches(Entity)` would drag the whole rule
into the GameTest tier where it needs a world, a server and 10 seconds. Taking the two fields keeps
it in `src/test` where it runs in milliseconds. This is the same move `Targeting.weightedRegionDist`
already made, and for the same reason.

### 4. Enemy targets are per-bot, and a bot created afterwards does not have one

`EnemyTarget` is stored on `Bot`, mirroring `targetPlayer`, which is the command this one is modelled
on. Because the record is immutable, every bot holds the same reference, so per-bot costs one field.

It inherits `playertarget`'s gap exactly: `/tplus enemytarget …` then `/tplus create …` leaves the
new bots hunting nothing. That is upstream's shipped behaviour for `playertarget` and copying it is
the conservative choice, but it is invisible, so `/tplus enemytarget` **reports per-label counts**
rather than one line — `3 x minecraft:zombie` beside `2 x nothing` is where an operator finds out.

### 5. `EquipmentTier` holds `Item` constants and builds no stacks

`new ItemStack(Items.X)` in a static initialiser throws "Components not bound yet" and breaks mod
loading outright. It also throws in a plain unit test, which is why `EquipmentTierTest` only ever
compares `Item` references — `ItemUtilsTest` documents the same boundary and is worth reading first.

### 6. An absent half of a tier is an empty array, and acceptance is separate from content

`LEATHER` has no tools, `WOOD` has no armour, and `NONE` has neither but must parse in both slots
because it is the filler. So `acceptsAsArmor()` is `this == NONE || armor.length > 0`, and
`armorPiece(i)` returns null past the end of the array. No entry in the table is ever null.

---

## Task 1: `EquipmentTier`

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/bot/EquipmentTier.java`
- Test: `src/test/java/net/nuggetmc/tplus/bot/EquipmentTierTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/net/nuggetmc/tplus/bot/EquipmentTierTest.java`:

```java
package net.nuggetmc.tplus.bot;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

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
                assertFalse(tier.tools().contains(null), tier.id() + " tools");
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
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
./gradlew test --tests '*EquipmentTierTest*'
```

Expected: compilation failure — `cannot find symbol: class EquipmentTier`.

- [ ] **Step 3: Write the enum**

Create `src/main/java/net/nuggetmc/tplus/bot/EquipmentTier.java`:

```java
package net.nuggetmc.tplus.bot;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
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

    /** Every tier nameable in an armour slot. The command's suggestions and its error both read this. */
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
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew test --tests '*EquipmentTierTest*'
```

Expected: BUILD SUCCESSFUL, 8 tests.

If instead it fails at class-load with a registry error rather than an assertion, the `Items`
constants need bootstrapping — but `ItemUtilsTest` already resolves them without one, so treat that
as a signal that something else changed and fix it there rather than adding a `@BeforeAll`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/EquipmentTier.java \
        src/test/java/net/nuggetmc/tplus/bot/EquipmentTierTest.java
git commit -m "feat: add the equipment tier table"
```

---

## Task 2: The tier reaches the bot

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/EquipmentTier.java` (add `equipArmor`)
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Mining.java:52-55, 411-426`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java`

Task 1 built the table and unit tested it. This task is the two places it is read: a bot's tools,
and a bot's four armour slots. The armour half needs a GameTest rather than a unit test because
equipping builds `ItemStack`s, and the unit tier cannot.

- [ ] **Step 1: Write the failing test**

Append to `BotActionTests`, **before the first `@GameTest` method in the outer class** — a test
appended into a nested class is silently not a test. Follow the file's existing `spawn` helper.

```java
    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("a_bot_mines_with_the_tier_it_was_given")
    static void a_bot_mines_with_the_tier_it_was_given(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));

        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        bot.setToolTier(EquipmentTier.NETHERITE);

        BlockPos target = new BlockPos(4, 1, 3);
        helper.setBlock(target, Blocks.STONE);

        // A direct preBreak call, not 200 ticks of hunting. move() adds Math.random() to every
        // jump, so a ticked test measures the walk rather than the tool choice.
        new Mining(registry.state(), registry.agent())
                .preBreak(bot, helper.absolutePos(target), ScanOffset.AT);

        helper.assertTrue(bot.getMainHandItem().is(Items.NETHERITE_PICKAXE),
                "a netherite bot must mine stone with its own pickaxe, not an iron one");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("a_bot_with_no_tools_mines_bare_handed")
    static void a_bot_with_no_tools_mines_bare_handed(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));

        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        bot.setToolTier(EquipmentTier.NONE);

        BlockPos target = new BlockPos(4, 1, 3);
        helper.setBlock(target, Blocks.STONE);

        new Mining(registry.state(), registry.agent())
                .preBreak(bot, helper.absolutePos(target), ScanOffset.AT);

        // `none` genuinely means none. optimalTool's loop runs zero times and returns EMPTY,
        // which is a different outcome from omitting the argument at create time -- that
        // leaves the iron default. Decision 1.
        helper.assertTrue(bot.getMainHandItem().isEmpty(),
                "the NONE tier must leave a bot bare-handed");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "7x5x7", floor = true)
    @TestHolder("the_default_tool_tier_is_upstreams_iron")
    static void the_default_tool_tier_is_upstreams_iron(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));

        // Nothing calls setToolTier. This is the test that keeps making the tier configurable
        // a change in capability rather than a change in behaviour.
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));

        BlockPos target = new BlockPos(4, 1, 3);
        helper.setBlock(target, Blocks.STONE);

        new Mining(registry.state(), registry.agent())
                .preBreak(bot, helper.absolutePos(target), ScanOffset.AT);

        helper.assertTrue(bot.getMainHandItem().is(Items.IRON_PICKAXE),
                "an unconfigured bot must still mine with iron");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 100)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("a_tier_lands_in_the_right_four_armour_slots")
    static void a_tier_lands_in_the_right_four_armour_slots(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(2, 1, 2));

        EquipmentTier.DIAMOND.equipArmor(bot);

        // EquipmentTierTest pins armorPiece(i) against ARMOR_SLOTS[i], but only in the table.
        // This is the pairing itself: transpose the two and a bot wears its boots on its head,
        // which nothing else in the codebase would notice and which no unit test can reach,
        // because equipping builds ItemStacks.
        helper.assertTrue(bot.getItemBySlot(EquipmentSlot.FEET).is(Items.DIAMOND_BOOTS), "feet");
        helper.assertTrue(bot.getItemBySlot(EquipmentSlot.LEGS).is(Items.DIAMOND_LEGGINGS), "legs");
        helper.assertTrue(bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), "chest");
        helper.assertTrue(bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "head");

        // NONE strips all four through the same loop that filled them -- armorPiece returns
        // null past the end of its empty array rather than throwing.
        EquipmentTier.NONE.equipArmor(bot);

        for (EquipmentSlot slot : EquipmentTier.ARMOR_SLOTS) {
            helper.assertTrue(bot.getItemBySlot(slot).isEmpty(), "the none tier must strip " + slot);
        }

        registry.reset();
        helper.succeed();
    }
```

Add whatever of these imports the file does not already have:

```java
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.Mining;
import net.nuggetmc.tplus.agent.legacy.ScanOffset;
import net.nuggetmc.tplus.bot.EquipmentTier;
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
./gradlew runGameTestServer
```

Expected: compilation failure — `cannot find symbol: method setToolTier(EquipmentTier)`.

- [ ] **Step 3: Add the field to `Bot`**

In `Bot.java`, beside the other per-bot target and item state (next to `targetPlayer`):

```java
    /**
     * What {@code Mining.optimalTool} may choose from.
     *
     * <p>Upstream had no such field: {@code LegacyItems} was one static iron set for every bot.
     * Defaulting to {@link EquipmentTier#IRON} is that set exactly, so making this configurable
     * changed what a bot <i>can</i> do and not what an unconfigured one does.
     */
    private EquipmentTier toolTier = EquipmentTier.IRON;
```

and the accessors, beside `getTargetPlayer`:

```java
    public EquipmentTier getToolTier() {
        return toolTier;
    }

    public void setToolTier(EquipmentTier tier) {
        this.toolTier = tier;
    }
```

- [ ] **Step 4: Add `equipArmor` to `EquipmentTier`**

The tier knows how to wear itself, rather than a command knowing how to dress a bot. That keeps the
index-to-slot pairing in the same file as the table it indexes, and makes it reachable from a
GameTest -- a private helper in `BotCommands` would not be.

```java
    /**
     * Puts this tier's four pieces on a bot, clearing any slot the tier has nothing for.
     *
     * <p>{@link #NONE} strips all four through this same loop: {@link #armorPiece(int)} returns
     * null past the end of its empty array.
     *
     * <p>Upstream wrote the Bukkit inventory <i>and</i> sent the equipment packets, with the
     * comment "packet sending to ensure"; {@code Bot.setItem(stack, slot)} already does both, so
     * one call per slot is enough.
     */
    public void equipArmor(Bot bot) {
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            Item piece = armorPiece(i);

            bot.setItem(piece == null ? ItemStack.EMPTY : new ItemStack(piece), ARMOR_SLOTS[i]);
        }
    }
```

Add `import net.minecraft.world.item.ItemStack;`.

- [ ] **Step 5: Move the tool list out of `Mining`**

Delete the `TOOLS` constant at `Mining.java:52-55` **and its javadoc at 39-51**, and move what that
javadoc says into `EquipmentTier` — the "Components not bound yet" paragraph is already there, and
the `LegacyItems` provenance is on `IRON`.

Change the signature and the loop at `Mining.java:411-426`:

```java
    /**
     * The fastest of {@code tier}'s tools against {@code target}, or an empty hand.
     *
     * <p>Ported from {@code preBreak}'s tool loop. Upstream compared Bukkit's
     * {@code Block.getDestroySpeed(tool)} against a starting value of 1, so a block no tool helps
     * with leaves the bot bare-handed. {@code ItemStack.getDestroySpeed(BlockState)} is the
     * vanilla equivalent and is the tool's multiplier for that block, same orientation.
     *
     * <p>The tier is a parameter rather than the static list upstream had, so two bots can carry
     * different tools. {@link EquipmentTier#NONE} has no tools and always returns an empty hand.
     */
    static ItemStack optimalTool(EquipmentTier tier, BlockState target) {
        ItemStack optimal = ItemStack.EMPTY;
        float optimalSpeed = 1;

        for (Item item : tier.tools()) {
            ItemStack tool = new ItemStack(item);
            float speed = tool.getDestroySpeed(target);

            if (speed > optimalSpeed) {
                optimal = tool;
                optimalSpeed = speed;
            }
        }

        return optimal;
    }
```

And the one call site, `Mining.java:97`:

```java
        bot.setItem(optimalTool(bot.getToolTier(), target));
```

Then fix imports: `Mining` no longer needs `net.minecraft.world.item.Items` **if nothing else in
the file uses it** — it does, `placeWaterDown` uses `Items.WATER_BUCKET`, so leave it. Add
`import net.nuggetmc.tplus.bot.EquipmentTier;`. `java.util.List` may now be unused; check before
removing it.

- [ ] **Step 6: Run the tests and watch them pass**

```bash
./gradlew runGameTestServer
```

Expected: all tests pass, four more than before. The whole run takes about 10 seconds.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/Bot.java \
        src/main/java/net/nuggetmc/tplus/bot/EquipmentTier.java \
        src/main/java/net/nuggetmc/tplus/agent/legacy/Mining.java \
        src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java
git commit -m "feat: give each bot its own tool tier and equipment"
```

---

## Task 3: `/tplus armor` onto the enum, and a new `/tplus tools`

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java:83-99` (the tables), `:222-228`
  (the `armor` registration), and the `armor` handler

- [ ] **Step 1: Delete the two tables**

Remove `ARMOR_TIERS` (lines 83-96) and `ARMOR_SLOTS` (98-99) entirely. `EquipmentTier.ARMOR_SLOTS`
replaces the second and the enum replaces the first. The `Items` import may become unused — check
the rest of the file before removing it.

- [ ] **Step 2: Add the two shared helpers**

Both are used by `armor`, `tools` and the `create` chain, so they go in once:

```java
    /** Suggests only the tiers that will actually parse in this slot. */
    private static SuggestionProvider<CommandSourceStack> tierSuggestions(boolean armor) {
        return (ctx, builder) -> {
            (armor ? EquipmentTier.armorTiers() : EquipmentTier.toolTiers()).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    /**
     * Resolves a tier name for one slot, or sends the failure and returns null.
     *
     * <p>The valid names come from the same predicate {@link #tierSuggestions} reads, so a tier
     * that tab-completes but then fails to parse cannot happen. Vanilla tiers are not symmetric —
     * there is no wooden chestplate and no chainmail pickaxe — so the two slots reject different
     * things and the message has to say which slot it is talking about.
     */
    private static @Nullable EquipmentTier tier(CommandSourceStack source, String name, boolean armor) {
        EquipmentTier tier = EquipmentTier.byName(name);
        boolean ok = tier != null && (armor ? tier.acceptsAsArmor() : tier.acceptsAsTools());

        if (!ok) {
            source.sendFailure(Component.literal("'" + name + "' is not a valid "
                    + (armor ? "armour" : "tools") + " tier. Available: "
                    + String.join(", ", armor ? EquipmentTier.armorTiers() : EquipmentTier.toolTiers())));
            return null;
        }

        return tier;
    }

```

Equipping itself is `EquipmentTier.equipArmor(Bot)`, added in Task 2 -- it stays on the enum so the
index-to-slot pairing lives beside the table it indexes, and so a GameTest can reach it.

Add imports:

```java
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.nuggetmc.tplus.bot.EquipmentTier;
import org.jetbrains.annotations.Nullable;
```

- [ ] **Step 3: Rewrite the `armor` handler and add `tools`**

Replace the body of `armor`:

```java
    /** Equips every bot with an armour tier. Ported from {@code armor}. */
    private static int armor(CommandContext<CommandSourceStack> ctx) {
        EquipmentTier tier = tier(ctx.getSource(), StringArgumentType.getString(ctx, "tier"), true);

        if (tier == null) {
            return 0;
        }

        Collection<Bot> bots = TerminatorPlus.registry().bots();
        bots.forEach(tier::equipArmor);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set armour tier '" + tier.id() + "' for " + bots.size() + " bot(s)"), true);
        return 1;
    }

    /**
     * Sets every bot's tool tier.
     *
     * <p>New: upstream had no equivalent, because its tool list was static. Without this, tools
     * would be the only one of the three equipment properties that can be set at spawn and never
     * changed afterwards.
     *
     * <p>This changes what a bot <i>holds</i> while mining, not how fast it mines —
     * {@code blockBreakEffect} is a fixed ten-stage ladder at two ticks a stage regardless of
     * tool or block hardness, which is upstream's design and is untouched.
     */
    private static int tools(CommandContext<CommandSourceStack> ctx) {
        EquipmentTier tier = tier(ctx.getSource(), StringArgumentType.getString(ctx, "tier"), false);

        if (tier == null) {
            return 0;
        }

        Collection<Bot> bots = TerminatorPlus.registry().bots();
        bots.forEach(bot -> bot.setToolTier(tier));

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set tool tier '" + tier.id() + "' for " + bots.size() + " bot(s)"), true);
        return 1;
    }
```

And the registrations — replace the existing `armor` block:

```java
        root.then(Commands.literal("armor")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .suggests(tierSuggestions(true))
                        .executes(BotCommands::armor)));

        root.then(Commands.literal("tools")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .suggests(tierSuggestions(false))
                        .executes(BotCommands::tools)));
```

- [ ] **Step 4: Build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, 84 unit tests plus the 8 from Task 1.

- [ ] **Step 5: Exercise both commands on a running server**

GameTests never register a command, so this tier is the only thing that runs the tree at all.

```bash
./gradlew runServer > /tmp/tplus-server.log 2>&1 &
python tools/rcon.py "tplus create Smith 2" "tplus armor copper" "tplus tools netherite" \
                     "tplus armor wood" "tplus tools chain" "tplus removeall" "stop"
```

Expected, in order: two bots; copper armour set for 2; netherite tools set for 2; **`'wood' is not a
valid armour tier. Available: none, leather, chain, copper, gold, iron, diamond, netherite`**;
**`'chain' is not a valid tools tier. Available: none, wood, stone, copper, gold, iron, diamond,
netherite`**; two removed.

The two rejections are the point of the step — they are what proves the slots differ.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/command/BotCommands.java
git commit -m "feat: add /tplus tools and move /tplus armor onto EquipmentTier"
```

---

## Task 4: The `create` chain

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java:119-132` (registration) and
  `:317-352` (the handler)

- [ ] **Step 1: Replace the registration**

Brigadier has no optional-in-the-middle argument, so this is a chain of six nodes, each of which
executes. The depth is passed to the handler because `CommandContext` has no "was this argument
present" query and throws on a missing name.

```java
        // Six nodes, each executable, because Brigadier has no optional-in-the-middle argument:
        // a chain grows left to right and reaching a later argument means typing the earlier
        // ones. `none` is the filler for the two word slots; <item> is last because it is the
        // only argument with no sensible filler.
        //
        // `playerlist` was a literal and is now a word argument taking `playerlist` or `none`,
        // so it occupies a fixed depth instead of hanging off every node. Same spelling, same
        // effect. The one casualty is `create <name> playerlist` with the count omitted, which
        // is now `create <name> 1 playerlist`.
        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> create(ctx, 1))
                        .then(Commands.argument("count",
                                        IntegerArgumentType.integer(1, MAX_BOTS_PER_COMMAND))
                                .executes(ctx -> create(ctx, 2))
                                .then(Commands.argument("playerlist", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            b.suggest("none");
                                            b.suggest("playerlist");
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> create(ctx, 3))
                                        .then(Commands.argument("armor", StringArgumentType.word())
                                                .suggests(tierSuggestions(true))
                                                .executes(ctx -> create(ctx, 4))
                                                .then(Commands.argument("tools", StringArgumentType.word())
                                                        .suggests(tierSuggestions(false))
                                                        .executes(ctx -> create(ctx, 5))
                                                        .then(Commands.argument("item",
                                                                        ItemArgument.item(event.getBuildContext()))
                                                                .executes(ctx -> create(ctx, 6)))))))));
```

- [ ] **Step 2: Replace the handler**

```java
    /**
     * Spawns bots, optionally equipped.
     *
     * @param depth how far along the six-node chain the parse got, and therefore which arguments
     *              exist. Brigadier offers no way to ask a {@link CommandContext} whether an
     *              argument was present — {@code getArgument} throws for a missing name — so the
     *              node that matched says so rather than the handler probing for it.
     */
    private static int create(CommandContext<CommandSourceStack> ctx, int depth)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        int count = depth >= 2 ? IntegerArgumentType.getInteger(ctx, "count") : 1;

        boolean playerList = false;

        if (depth >= 3) {
            String word = StringArgumentType.getString(ctx, "playerlist");

            if (word.equalsIgnoreCase("playerlist")) {
                playerList = true;
            } else if (!word.equalsIgnoreCase("none")) {
                source.sendFailure(Component.literal(
                        "'" + word + "' must be 'playerlist' or 'none'"));
                return 0;
            }
        }

        // The two defaults are not symmetric, and cannot be. An omitted armour argument means no
        // armour, which is what a bot has always spawned with; an omitted tools argument means
        // IRON, which is also what a bot has always spawned with. Typing `none` for tools is a
        // third thing -- genuinely no tools. Decision 1.
        EquipmentTier armor = EquipmentTier.NONE;
        EquipmentTier tools = EquipmentTier.IRON;

        if (depth >= 4) {
            armor = tier(source, StringArgumentType.getString(ctx, "armor"), true);

            if (armor == null) {
                return 0;
            }
        }

        if (depth >= 5) {
            tools = tier(source, StringArgumentType.getString(ctx, "tools"), false);

            if (tools == null) {
                return 0;
            }
        }

        // Built here, on the command thread, rather than inside the async skin callback: the
        // callback runs on a worker until onServerThread hands it back, and ItemStack
        // construction reads data components.
        ItemStack item = depth >= 6
                ? ItemArgument.getItem(ctx, "item").createItemStack(1)
                : ItemStack.EMPTY;

        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        MinecraftServer server = source.getServer();

        boolean inList = playerList;
        EquipmentTier armorTier = armor;
        EquipmentTier toolTier = tools;

        source.sendSuccess(() -> Component.literal("Fetching skin for " + name + "..."), false);

        MojangSkins.fetch(name).thenAccept(skin -> BotRegistry.onServerThread(server, () -> {
            // Scatter factor, from BotManagerImpl.createBots: bots after the first get a
            // nudge so a batch spawned on one spot does not stack up.
            double f = count < 100 ? 0.004 * count : 0.4;

            for (int i = 1; i <= count; i++) {
                String botName = BotGameProfiles.indexedName(name, i);
                GameProfile profile = BotGameProfiles.create(botName, skin);

                Bot bot = BotFactory.spawn(TerminatorPlus.registry(), level, pos,
                        source.getRotation().y, source.getRotation().x, profile, inList);

                bot.setToolTier(toolTier);
                armorTier.equipArmor(bot);

                if (!item.isEmpty()) {
                    // Same pair /tplus give does: the default item is what setItem(null)
                    // restores and what ItemUtils scores for damage, and putting it in hand now
                    // is what someone typing the command expects.
                    bot.setDefaultItem(item.copy());
                    bot.setItem(null);
                }

                if (i > 1) {
                    bot.getBotVelocity()
                            .setX(Math.random() - 0.5)
                            .setY(0.5)
                            .setZ(Math.random() - 0.5)
                            .normalize()
                            .multiply(f);
                }
            }

            source.sendSuccess(() -> Component.literal("Spawned " + count + " bot(s)"
                    + (inList ? " in the player list" : "")
                    + " with " + armorTier.id() + " armour, " + toolTier.id() + " tools"
                    + (item.isEmpty() ? "" : " and " + item.getHoverName().getString())), true);
        }));

        return count;
    }
```

- [ ] **Step 3: Build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Walk the whole chain on a running server**

```bash
./gradlew runServer > /tmp/tplus-server.log 2>&1 &
python tools/rcon.py \
  "tplus create A" \
  "tplus create B 2" \
  "tplus create C 1 playerlist" \
  "tplus create D 1 none diamond" \
  "tplus create E 1 none diamond netherite" \
  "tplus create F 1 none none none minecraft:bow" \
  "tplus create G 1 none wood diamond" \
  "tplus create H 1 maybe" \
  "tplus list" "tplus removeall" "stop"
```

Expected: A–F succeed, each reporting its own gear; `C` says "in the player list"; `A`, `B` and `C`
report `none armour, iron tools`; `F` reports `none armour, none tools and Bow`. `G` fails with the
armour-tier message. `H` fails with `'maybe' must be 'playerlist' or 'none'`.

The log must contain **no `Ambiguity` warning** for the `create` node. A word argument and an
integer argument at the same depth would produce one; `count` and `playerlist` are at different
depths, so there should be none.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/command/BotCommands.java
git commit -m "feat: equip bots from /tplus create"
```

---

## Task 5: `EnemyTarget`

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/bot/EnemyTarget.java`
- Test: `src/test/java/net/nuggetmc/tplus/bot/EnemyTargetTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
        // The type is not consulted at all for an id match, which is what lets `specific`
        // pin a boat-riding skeleton or anything else without listing its type.
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
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
./gradlew test --tests '*EnemyTargetTest*'
```

Expected: compilation failure — `cannot find symbol: class EnemyTarget`.

- [ ] **Step 3: Write the record**

```java
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
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew test --tests '*EnemyTargetTest*'
```

Expected: BUILD SUCCESSFUL, 5 tests.

If it fails at class-load resolving `EntityTypes.ZOMBIE`, the entity registry needs bootstrapping
where the item registry did not; add `net.minecraft.server.Bootstrap.bootStrap()` in a
`@BeforeAll` and say so in the class javadoc. Do not move the test to the GameTest tier — the
point of decision 3 is that this rule stays here.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/EnemyTarget.java \
        src/test/java/net/nuggetmc/tplus/bot/EnemyTargetTest.java
git commit -m "feat: add the enemy target value type"
```

---

## Task 6: `TargetGoal.ENTITY`

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/TargetGoal.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Targeting.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Create: `src/gametest/java/net/nuggetmc/tplus/gametest/EnemyTargetTests.java`

- [ ] **Step 1: Write the failing tests**

Create `src/gametest/java/net/nuggetmc/tplus/gametest/EnemyTargetTests.java`:

```java
package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.TargetGoal;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.bot.EnemyTarget;

import java.util.Set;

/**
 * The ENTITY goal in a world.
 *
 * <p>Every test here calls {@code locateTarget} directly rather than ticking a registry and
 * asserting on where a bot ended up. {@code move()} adds {@code Math.random()} to every jump, so
 * a ticked test measures the walk; these measure the decision.
 *
 * <p>{@code EnemyTarget.matches} is unit tested without a world. What needs a world is the rest
* of the branch: the entity scan, the self-exclusion, and what happens to a target that dies.
 *
 * <p>Nothing here touches {@code Targeting.CUSTOM_MOB_LIST} or {@code customListMode}, which are
 * static and shared across the suite's one JVM -- the ENTITY goal reads neither, which is the
 * point of keeping the two features' state apart. No {@code finally} is needed; add one the
 * moment a test here does touch them.
 */
@ForEachTest(groups = EnemyTargetTests.GROUP)
public final class EnemyTargetTests {

    public static final String GROUP = "bot.enemytarget";

    private EnemyTargetTests() {
    }

    private static BotRegistry registryWithEntityGoal() {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        agent.targeting().setTargetType(TargetGoal.ENTITY);
        registry.setAgent(agent);
        return registry;
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry,
                             BlockPos relative, String name) {
        Bot bot = BotFactory.spawn(registry, helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(relative)), 0f, 0f,
                BotGameProfiles.create(name, null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    private static LivingEntity locate(BotRegistry registry, Bot bot) {
        return ((LegacyAgent) registry.agent()).targeting().locateTarget(bot, bot.position());
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_generic_target_finds_its_type")
    static void a_generic_target_finds_its_type(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(8, 1, 5));

        bot.setEnemyTarget(EnemyTarget.ofTypes(Set.of(EntityTypes.ZOMBIE), "minecraft:zombie"));

        helper.assertTrue(locate(registry, bot) == zombie,
                "a generic zombie target must find the zombie");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_generic_target_ignores_a_type_it_does_not_name")
    static void a_generic_target_ignores_a_type_it_does_not_name(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");

        // The cow is closer. If the branch scanned without consulting the target it would win.
        Cow cow = helper.spawn(EntityTypes.COW, new BlockPos(3, 1, 5));
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(9, 1, 5));

        bot.setEnemyTarget(EnemyTarget.ofTypes(Set.of(EntityTypes.ZOMBIE), "minecraft:zombie"));

        LivingEntity found = locate(registry, bot);

        helper.assertTrue(found == zombie, "the further zombie must beat the nearer cow");
        helper.assertFalse(found == cow, "a cow is not a zombie");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_specific_target_finds_one_entity_by_id")
    static void a_specific_target_finds_one_entity_by_id(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");

        Zombie near = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(3, 1, 5));
        Zombie far = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(9, 1, 5));

        // The far one, deliberately: this is what separates `specific` from `generic`. A
        // generic zombie target would pick `near`.
        bot.setEnemyTarget(EnemyTarget.ofEntities(Set.of(far.getUUID()), "1 entities (zombie)"));

        LivingEntity found = locate(registry, bot);

        helper.assertTrue(found == far, "a specific target must find the entity it names");
        helper.assertFalse(found == near, "and not a nearer one of the same type");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_dead_specific_target_yields_no_target")
    static void a_dead_specific_target_yields_no_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(8, 1, 5));

        bot.setEnemyTarget(EnemyTarget.ofEntities(Set.of(zombie.getUUID()), "1 entities (zombie)"));
        helper.assertTrue(locate(registry, bot) == zombie, "alive, it is the target");

        zombie.kill(helper.getLevel());

        // No fallback. validateCloserEntity's isAlive() check does all of this, which is why
        // the branch adds nothing for it -- and it is what the PLAYER branch actually does,
        // as opposed to what PLAYER's enum description claims.
        helper.assertTrue(locate(registry, bot) == null, "dead, there is no target at all");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_bot_does_not_target_itself")
    static void a_bot_does_not_target_itself(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(5, 1, 5), "Hunter");

        // Bots are ServerPlayers, so `generic player` reaches them -- which is intended for
        // other bots and absurd for this one. Without the bot != entity guard a bot would
        // stand still hunting itself.
        bot.setEnemyTarget(EnemyTarget.ofTypes(Set.of(EntityTypes.PLAYER), "minecraft:player"));

        helper.assertTrue(locate(registry, bot) == null, "a bot must never be its own target");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("another_bot_is_a_valid_target")
    static void another_bot_is_a_valid_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        Bot quarry = spawn(helper, registry, new BlockPos(8, 1, 5), "Quarry");

        // The other half of the self-exclusion: only self is excluded, not every bot.
        hunter.setEnemyTarget(EnemyTarget.ofEntities(Set.of(quarry.getUUID()), "1 entities (player)"));

        helper.assertTrue(locate(registry, hunter) == quarry,
                "a specific target must be able to name another bot");

        registry.reset();
        helper.succeed();
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
./gradlew runGameTestServer
```

Expected: compilation failure — `cannot find symbol: variable ENTITY`.

- [ ] **Step 3: Add the goal constant**

In `TargetGoal.java`, between `PLAYER` and `NONE` — the two "a target was named for you" goals sit
together, and `NONE` stays last:

```java
    PLAYER("Target a single player. Defaults to NEAREST_VULNERABLE_PLAYER if no player found."),
    ENTITY("Target the entities or entity types set by /tplus enemytarget. No target if none match."),
    NONE("No target goal.");
```

Update the class javadoc, because it currently claims the enum is verbatim:

```java
/**
 * Ported from {@code EnumTargetGoal}; only the name is shortened.
 *
 * <p>{@link #ENTITY} is the one addition, for {@code /tplus enemytarget}, which upstream had no
 * equivalent of. Note that {@link #PLAYER}'s description is upstream's and is wrong: the branch
 * does not fall back to NEAREST_VULNERABLE_PLAYER, it returns null. ENTITY's description says
 * what its branch does.
 */
```

- [ ] **Step 4: Add the field to `Bot`**

Beside `targetPlayer`:

```java
    /**
     * What the {@code ENTITY} goal chases, set by {@code /tplus enemytarget}.
     *
     * <p>Per-bot, mirroring {@link #targetPlayer}, and it inherits that field's gap: a bot
     * created after the command was run carries {@link EnemyTarget#NONE} and hunts nothing.
     * {@code /tplus enemytarget} reports per-label counts so that is visible rather than
     * mysterious.
     */
    private EnemyTarget enemyTarget = EnemyTarget.NONE;
```

and, beside `setTargetPlayer`:

```java
    public EnemyTarget getEnemyTarget() {
        return enemyTarget;
    }

    public void setEnemyTarget(EnemyTarget target) {
        this.enemyTarget = target;
    }
```

- [ ] **Step 5: Add the `Targeting` branch**

In the `switch` in `locateTarget`, after `case PLAYER`:

```java
            case ENTITY: {
                EnemyTarget enemy = bot.getEnemyTarget();

                if (!enemy.isEmpty()) {
                    for (LivingEntity entity : livingEntities(level)) {
                        // `bot != entity` or `generic player` makes every bot target itself and
                        // stand still. Other bots are deliberately not excluded: bots are
                        // ServerPlayers, and naming one with `specific` is the point.
                        if (bot != entity
                                && enemy.matches(entity.getType(), entity.getUUID())
                                && validateCloserEntity(bot, entity, pos, result)) {
                            result = entity;
                        }
                    }
                }

                break;
            }
```

Note the fourth argument is `result`, not `null`. The `PLAYER` branch passes `null` so that a named
player is chased at any range; here there can be several candidates, so the incumbent has to be
compared against — nearest-of-set. With a set of one the first candidate meets a null incumbent
anyway and range is skipped, which makes a single specific target behave exactly like `PLAYER`.

Add `import net.nuggetmc.tplus.bot.EnemyTarget;`.

- [ ] **Step 6: Run the tests and watch them pass**

```bash
./gradlew runGameTestServer
```

Expected: all tests pass, six more than before.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy/TargetGoal.java \
        src/main/java/net/nuggetmc/tplus/agent/legacy/Targeting.java \
        src/main/java/net/nuggetmc/tplus/bot/Bot.java \
        src/gametest/java/net/nuggetmc/tplus/gametest/EnemyTargetTests.java
git commit -m "feat: add the ENTITY target goal"
```

---

## Task 7: `/tplus enemytarget`

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`

- [ ] **Step 1: Register the subtree**

Beside `playertarget`:

```java
        // Two modes rather than one command that guesses. `generic` is live and by type, so a
        // zombie that spawns later is hunted; `specific` is fixed and by entity, so it is not.
        // The mode is stated because the two answer the same question opposite ways.
        root.then(Commands.literal("enemytarget")
                .executes(BotCommands::showEnemyTarget)
                .then(Commands.literal("clear")
                        .executes(BotCommands::clearEnemyTarget))
                .then(Commands.literal("generic")
                        .then(Commands.argument("type", ResourceOrTagArgument.resourceOrTag(
                                        event.getBuildContext(), Registries.ENTITY_TYPE))
                                .executes(BotCommands::enemyTargetGeneric)))
                .then(Commands.literal("specific")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(BotCommands::enemyTargetSpecific))));
```

- [ ] **Step 2: Write the four handlers**

```java
    /**
     * Points every live bot at a type or a tag, live.
     *
     * <p>{@code ResourceOrTagArgument} is what makes {@code #minecraft:raiders} work alongside
     * {@code zombie}, and it is why this command needs no add/remove/list family of its own.
     */
    private static int enemyTargetGeneric(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ResourceOrTagArgument.Result<EntityType<?>> result =
                ResourceOrTagArgument.getResourceOrTag(ctx, "type", Registries.ENTITY_TYPE);

        // Expanded to concrete types here rather than kept as the Predicate the Result already
        // is: an expanded set can be reported by /tplus enemytarget and tested without a world,
        // and a tag only changes on a datapack reload anyway.
        Set<EntityType<?>> types = result.unwrap().map(
                ref -> Set.<EntityType<?>>of(ref.value()),
                tag -> tag.stream().map(Holder::value).collect(Collectors.toUnmodifiableSet()));

        if (types.isEmpty()) {
            // An empty tag would set a target that silently matches nothing, which is the
            // failure mode this whole command exists to avoid.
            ctx.getSource().sendFailure(Component.literal(
                    result.asPrintable() + " is empty, so nothing would be targeted."));
            return 0;
        }

        String label = result.asPrintable()
                + (types.size() > 1 ? " (" + types.size() + " types)" : "");

        return applyEnemyTarget(ctx, EnemyTarget.ofTypes(types, label));
    }

    /**
     * Points every live bot at the entities a selector matched, right now.
     *
     * <p>{@code limit=1} and a bare UUID are the same mechanism with a set of one.
     */
    private static int enemyTargetSpecific(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<? extends Entity> selected = EntityArgument.getEntities(ctx, "targets");

        // locateTarget returns a LivingEntity and @e matches boats and item frames. Filtering
        // here rather than at scan time means the operator is told, instead of watching a
        // successful command do nothing.
        List<Entity> living = selected.stream().filter(e -> e instanceof LivingEntity).toList();

        if (living.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "None of the " + selected.size() + " selected entities can be targeted."));
            return 0;
        }

        int ignored = selected.size() - living.size();

        if (ignored > 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Ignored " + ignored + " selected entities that cannot be targeted."), false);
        }

        Set<UUID> ids = living.stream().map(Entity::getUUID).collect(Collectors.toUnmodifiableSet());
        String types = living.stream()
                .map(e -> EntityType.getKey(e.getType()).getPath())
                .distinct().sorted().collect(Collectors.joining(", "));

        return applyEnemyTarget(ctx, EnemyTarget.ofEntities(ids,
                living.size() + " entities (" + types + ")"));
    }

    /** Sets the target on every bot and switches the goal to match. */
    private static int applyEnemyTarget(CommandContext<CommandSourceStack> ctx, EnemyTarget target) {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        Collection<Bot> bots = TerminatorPlus.registry().bots();
        bots.forEach(bot -> bot.setEnemyTarget(target));
        agent.targeting().setTargetType(TargetGoal.ENTITY);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Now hunting " + target.label() + " for " + bots.size()
                        + " bot(s). Goal set to ENTITY."), true);
        return 1;
    }

    /**
     * Reports the enemy target, grouped by label.
     *
     * <p>Grouped rather than assumed uniform on purpose: a bot created after the command carries
     * {@code EnemyTarget.NONE}, the same gap {@code /tplus playertarget} has, and this is where
     * an operator finds out — "3 x minecraft:zombie" beside "2 x nothing".
     */
    private static int showEnemyTarget(CommandContext<CommandSourceStack> ctx) {
        Collection<Bot> bots = TerminatorPlus.registry().bots();

        if (bots.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No bots are loaded."), false);
            return 0;
        }

        Map<String, Integer> counts = new TreeMap<>();

        for (Bot bot : bots) {
            counts.merge(bot.getEnemyTarget().label(), 1, Integer::sum);
        }

        String summary = counts.entrySet().stream()
                .map(e -> e.getValue() + " x " + e.getKey())
                .collect(Collectors.joining("\n  "));

        ctx.getSource().sendSuccess(() -> Component.literal("Enemy target:\n  " + summary), false);
        return 1;
    }

    /** Clears the enemy target, leaving the goal alone. */
    private static int clearEnemyTarget(CommandContext<CommandSourceStack> ctx) {
        Collection<Bot> bots = TerminatorPlus.registry().bots();
        bots.forEach(bot -> bot.setEnemyTarget(EnemyTarget.NONE));

        // The goal is deliberately not put back. Setting a target has one right answer for the
        // goal; clearing one does not, and silently re-aiming every bot at the nearest player as
        // a side effect of a clear is worse than a goal that finds nothing until /tplus goal
        // says otherwise.
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared the enemy target for " + bots.size()
                        + " bot(s). The goal is unchanged."), true);
        return 1;
    }
```

Add imports:

```java
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nuggetmc.tplus.bot.EnemyTarget;

import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
```

- [ ] **Step 3: Make `playertarget` set the goal too**

Replace the tail of `setPlayerTarget`:

```java
    /**
     * Points every live bot at one player and selects the PLAYER goal.
     *
     * <p>Ported from {@code settings playertarget}. <b>Divergence:</b> upstream set the target
     * and then told the operator to go and set the goal themselves. Doing it here makes the two
     * targeting commands behave alike, which matters more than keeping upstream's message —
     * two adjacent commands that differ on whether they finish the job are worse than one
     * command that differs from upstream.
     */
    private static int setPlayerTarget(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");

        for (Bot bot : TerminatorPlus.registry().bots()) {
            bot.setTargetPlayer(player.getUUID());
        }

        agent.targeting().setTargetType(TargetGoal.PLAYER);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "All bots now target " + player.getGameProfile().name()
                        + ". Goal set to PLAYER."), true);
        return 1;
    }
```

- [ ] **Step 4: Build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Exercise the tree on a running server**

```bash
./gradlew runServer > /tmp/tplus-server.log 2>&1 &
python tools/rcon.py \
  "summon zombie ~ ~ ~5" "summon zombie ~ ~ ~7" "summon cow ~ ~ ~9" \
  "tplus create Hunter 2" \
  "tplus enemytarget generic zombie" \
  "tplus goal" \
  "tplus enemytarget" \
  "tplus create Latecomer 1" \
  "tplus enemytarget" \
  "tplus enemytarget generic #minecraft:raiders" \
  "tplus enemytarget specific @e[type=zombie]" \
  "tplus enemytarget specific @e[type=cow,limit=1]" \
  "tplus enemytarget clear" \
  "tplus goal" \
  "tplus removeall" "stop"
```

Expected, in order: the summons; two bots; `Now hunting minecraft:zombie for 2 bot(s). Goal set to
ENTITY.`; `Goal: ENTITY`; `2 x minecraft:zombie`; a third bot; **`2 x minecraft:zombie` beside
`1 x nothing`** — that line is the whole point of step 5, it is decision 4 made visible; the raiders
tag reporting several types; `3 entities (zombie)` — note it counts bots too if any are in range, so
read the type list; `1 entities (cow)`; the clear; and `Goal: ENTITY` still, unchanged by the clear.

No `Ambiguity` warning for the `enemytarget` node. The three children are all literals, so there
should be none.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/command/BotCommands.java
git commit -m "feat: add /tplus enemytarget and make both targeting commands set the goal"
```

---

## Task 8: The client pass

No code. This is the tier that caught four bugs that 132 GameTests and three RCON sessions did not,
and this plan touches armour rendering and equipment packets, so it is not optional.

- [ ] **Step 1: Build the production jar and stand up a real server**

The dev run is not representative — it bundles the gametest source set and a vanilla client is
refused by it. Follow `tools/README.md`, including the Windows `win_args.txt` warning.

```bash
./gradlew jar
```

- [ ] **Step 2: Check the things only a screen can check**

Join, op yourself **after** joining (offline mode guesses a UUID for a name it has not seen), then:

```
/tplus create Knight 3 none netherite diamond minecraft:bow
```

- [ ] All three bots wear netherite, all four pieces, visible on the model.
- [ ] All three hold a bow.
- [ ] `/tplus tools none` then watch one mine — bare hands, no phantom item.
- [ ] `/tplus armor leather` restyles them and `/tplus armor none` strips them, with no ghost
      pieces left behind. Equipment packets are sent by hand for bots, so a stale slot is exactly
      the kind of thing this tier exists to catch.
- [ ] `/tplus enemytarget generic zombie` with a zombie nearby: the bots go for the zombie and
      ignore you.
- [ ] `/tplus enemytarget specific @e[type=zombie,limit=1]`: they commit to one zombie and stay on
      it past other zombies.
- [ ] Kill it: they stop, rather than switching to you.

- [ ] **Step 3: Write down anything that differs**

A finding here is a new task, not a note. Add it before moving on.

---

## Task 9: Docs

**Files:**
- Modify: `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` (the register)
- Modify: `CLAUDE.md`
- Modify: `docs/backlog.md`

- [ ] **Step 1: Extend Plan B's deviation register**

Continuing from 21:

```markdown
22. `/tplus create` takes armour, tools and a held item. Upstream had no equipment at spawn at all,
    and the chain's `none` filler exists because Brigadier has no optional-in-the-middle argument
    (Plan D decision 1).
23. `playerlist` is an argument rather than a literal and sits at a fixed depth, so
    `create <name> playerlist` with the count omitted no longer parses. It is
    `create <name> 1 playerlist`.
24. Tools are per-bot and tiered, where upstream's `LegacyItems` was one static iron set. The
    default is still that set, so an unconfigured bot is unchanged. Omitting the argument means
    iron; typing `none` means no tools (Plan D decision 1).
25. `/tplus tools` is new. Upstream had no equivalent, because its tool list could not vary.
26. `TargetGoal.ENTITY` is a new constant on an enum otherwise ported verbatim from
    `EnumTargetGoal`.
27. `/tplus enemytarget` is new. Upstream could name one player or a list of mob types and had no
    way to name a specific entity. It deliberately does not reuse `CUSTOM_MOB_LIST` (Plan D
    decision 4 and the spec).
28. `/tplus playertarget` now sets the goal to PLAYER instead of telling the operator to.
```

And record, not as a deviation:

> Found while planning Plan D: `TargetGoal.PLAYER`'s description claims it falls back to
> `NEAREST_VULNERABLE_PLAYER` when no player is found. It does not — the branch returns null.
> The description is upstream's and is kept; the class javadoc now says it is wrong so the next
> reader does not trust it.

- [ ] **Step 2: Add the 26.2 notes to `CLAUDE.md`**

Into the "26.2 API notes" list:

```markdown
- `ServerLevel.getEntity(UUID)` is `getEntityInAnyDimension(UUID)`. `getEntity(int)` is still the
  network-id lookup.
- `TagKey` kept `.location()` while `ResourceKey` moved to `.identifier()` — visible side by side
  in `ResourceOrTagArgument`'s two `asPrintable()` implementations.
- `ResourceOrTagArgument` accepts a type or a `#tag` and `Result.unwrap()` gives an
  `Either<Holder.Reference, HolderSet.Named>`, so a tag can be expanded to concrete values rather
  than kept as a predicate.
```

Into the unit-test row of the test-tier table, or beside it:

```markdown
`Items.X` and `EntityTypes.X` constants resolve in a plain unit test; `new ItemStack(item)` does
not ("Components not bound yet"). Design value types to be testable on that side of the line —
`EnemyTarget.matches` takes a type and a UUID rather than an `Entity` for exactly this reason.
```

- [ ] **Step 3: Update `docs/backlog.md`**

Delete the **Loadouts** and **Targeting a specific entity** sections — both are now built. In
**Ranged attacks**, note that `/tplus create … minecraft:bow` now arms a bot with one and the
missing piece is still the use-tick. In **Bots have no self-preservation**, note armour now exists
but nothing reads health, so it changes how long they last and not what they do.

- [ ] **Step 4: Full verification from clean**

```bash
./gradlew clean build
./gradlew runGameTestServer
```

Expected: 84 + 13 unit tests (8 from Task 1, 5 from Task 5), 132 + 10 GameTests (4 from Task 2,
6 from Task 6), all passing.

- [ ] **Step 5: Commit**

```bash
git add docs CLAUDE.md
git commit -m "docs: record Plan D's deviations and the 26.2 notes it turned up"
```

---

## What is still not done after this plan

Unchanged from Plan C, minus the two backlog items this closes:

- **The public API module.** `Terminator`, `BotManager`, `TerminatorPlusAPI`, `InternalBridge`,
  `AIManager`. Still the item with the strongest case for being next.
- **The neural-network AI** and `/tplus ai`.
- **`Debugger`** (497 lines).
- **Narrowing `AgentState`.**
- **Persistence**, of any list, including the new enemy target.
- **The use-tick**, which food, potions, bows and the shield all still wait on. This plan arms a bot
  with a bow; it does not make it shoot one.
