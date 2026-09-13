# Shears — design

One more feature upstream never had. Upstream's `LegacyItems` was a pickaxe, an axe and a shovel;
Plan D made that set a per-bot tier and made break progress the held tool's destroy speed. Neither
touched the fact that **nothing in the set helps against leaves**, so a bot pathing through a forest
spends six seconds a block.

Not a translation, so `paper-original` has nothing to check against and this document stands in for
it, the same way `2026-09-13-loadouts-and-entity-targeting-design.md` does.

**Branch:** `master`. **Stack:** Java 25, NeoForge 26.2.0.87, Minecraft 26.2, JUnit 6.1.3, NeoForge
`testframework`.

**Backlog:** closes nothing. Adds one entry — *Break speed ignores hardness* — which the audit below
quantifies for the first time.

---

## The problem, in ticks

`Mining.blockBreakEffect` accrues the held stack's destroy speed against a fixed cost:

```
progress per run = max(1, round(speed × BREAK_PERIOD))     BREAK_PERIOD = 2
ticks            = BREAK_PERIOD × ceil(BREAK_COST / progress)   BREAK_COST = 120
```

No pickaxe, axe or shovel scores above 1.0 on leaves, so `Mining.optimalTool` returns an empty
stack and the bot mines them bare-handed at 1.0 — **2 progress a run, 60 runs, 120 ticks a block.**
Shears score 15.0, which is 30 a run and **8 ticks**.

| Block | Today | With shears |
|---|---|---|
| Leaves | 120t | **8t** |
| Cobweb | 120t | **8t** |
| Wool | 120t | **24t** |

---

## The audit

Scope was settled by measuring rather than guessing. Vanilla's `mineable/*` tags were resolved
recursively against all 1,184 blocks in the registry, taken from the patched jar's
`assets/minecraft/lang/en_us.json`. The blocks a bot never actually spends 120 ticks on were then
subtracted — `BREAK`, `INSTANT_BREAK_BLOCKS`, `NO_CRACK` and `Mining.UNBREAKABLE`, parsed straight
out of the Java source so nothing was transcribed by hand.

832 of the 1,184 are covered by the three tier tools. Of the 352 that are not, **221 are blocks the
bot really does grind through at 1.0**:

| Bucket | Blocks | What they are | Fix |
|---|---|---|---|
| Shears | **28** | 11 leaves, 16 wool, cobweb | 120t → 8t / 24t |
| Hoe only | 18 | sculk family ×6, moss ×4, sponge ×2, hay, shroomlight, target, nether and warped wart, dried kelp | 120t → 20t iron |
| **Nothing** | **175** | glass and panes ×35, candles and candle cakes ×34, beds ×16, carpets ×16, heads and skulls ×14, and 60 more — glowstone, sea lantern, froglights, chain, redstone lamp, end rod, reinforced deepslate, vaults | — |

Three conclusions, and they decided the scope:

**Shears are the only bucket that occurs in terrain.** Leaves are in every forest and jungle; cobweb
is mineshafts and strongholds. Wool, glass and carpet are player builds. Sculk is the deep dark and
moss is lush caves. For "a bot pathing toward its target", leaves *are* the problem.

**The hoe is nearly free and nearly pointless.** It adds nothing for leaves that shears do not
already beat 15.0 to 9.0, it is tiered — seven more table entries against shears' one untiered item
— and its 18 blocks are almost all builds or one biome.

**The 175 are not a tool problem.** Glass has hardness 0.3 and breaks instantly for a player. It
costs a bot six seconds because `blockBreakEffect` ignores `BlockState.getDestroySpeed()` entirely.
Deviation 26 says so outright: *"Block hardness is still ignored, as upstream ignored it."* No tool
fixes those; only reading hardness does, and that is a different change with a different risk — see
*Backlog* below.

---

## Scope

**In:**

- `Items.SHEARS` as a fourth mining candidate on every tier that has tools, via a new
  `EquipmentTier.miningTools()`.
- `Mining.optimalTool` choosing from that set instead of `tools()`.

**Out:**

- **A hoe.** 18 blocks, tiered, and beaten by shears on the only one that matters.
- **Reading hardness.** It would change the remaining 175 rather than uniformly fix them — glass
  becomes near-instant, reinforced deepslate far slower — and it would change the break time of
  every block in the game, including the iron-equals-twenty-ticks anchor that `STAGE_COST` is
  defined from. Backlog.
- **Putting shears in the tier table.** See below — it breaks a faithfulness pin.
- **Durability, enchantments, an off-hand slot.** Unchanged from Plan D's spec.
- **A command.** Shears are not configurable: they are there whenever the tier has tools, which
  after `asToolTier()`'s floor means every bot the commands can produce.

---

## The change

### `EquipmentTier.miningTools()`

New, alongside an untouched `tools()`:

```java
/**
 * Everything a bot on this tier may mine with: {@link #tools()} plus shears.
 *
 * <p>Shears are untiered, so they are not in the table. Vanilla has exactly one pair — no
 * wooden shears and no netherite shears — and their speed comes from a Tool component built
 * by {@code ShearsItem.createToolProperties} rather than from a ToolMaterial: 15.0 on
 * #minecraft:leaves and cobweb, 5.0 on #minecraft:wool, 2.0 on glow lichen and vine, 1.0 on
 * everything else.
 *
 * <p>Empty when {@link #tools()} is empty, so NONE, LEATHER and CHAIN yield nothing rather
 * than a nonsensical shears-only set. That is today's behaviour preserved, not a new rule: a
 * tier with no tools already mines bare-handed. The commands cannot reach the case —
 * asToolTier floors NONE to WOOD and the tools slot rejects the other two — but setToolTier
 * is public and applies only that one floor, so a caller can still get there.
 */
public List<Item> miningTools()
```

Computed once per constant in the constructor, not rebuilt per call. That is safe: `Items.SHEARS`
is an `Item` constant, and the constructor already dereferences `Items.WOODEN_PICKAXE` in exactly
that position. CLAUDE.md's "Components not bound yet" trap is about `new ItemStack` in a static
initialiser, which this is not.

**Why a second accessor rather than a fourth entry in the seven arrays.** `tools()` means "upstream's
`LegacyItems` set, per tier", and `EquipmentTierTest.toolsArePickaxeAxeShovelInThatOrder` pins
`IRON.tools()` as exactly pickaxe, axe, shovel with the comment *"IRON is upstream's LegacyItems set
verbatim and this is what pins it."* Putting shears in the table would repeat one constant seven
times, assert something false — shears have no tier — and destroy that pin. It would also leave a
trap live: `acceptsAsTools()` is `this == NONE || tools.length > 0`, so shears in `LEATHER` or
`CHAIN` would silently make them parse in the tools slot of `/tplus create`.

### `Mining.optimalTool`

One line: iterate `tier.miningTools()` instead of `tier.tools()`. The javadoc gains a sentence on
why shears are a candidate and why they never displace a tier tool.

---

## Why it cannot regress

`optimalTool` starts at `optimalSpeed = 1` and replaces only on `speed > optimalSpeed`. Shears score
1.0 on 1,154 of the 1,184 blocks, so they can win only on the 30 where they exceed it. Two of those
30 are glow lichen and vine, which are `mineable/axe` — an iron axe scores 6.0 against shears' 2.0,
and even a wooden axe's 2.0 holds the slot, because a tie is not `>` and the tier tools are iterated
first. So **shears change the choice on exactly the 28 audited blocks and nowhere else.**

No pickaxe, axe or shovel scores above 1.0 on any of those 28 — that is what put them in the bucket.
So no existing choice moves, and the three GameTests pinning "a netherite bot uses its own pickaxe",
"an unconfigured bot uses iron" and "none floors to wood" hold by construction.

---

## Drops and durability

Both unchanged, and both worth a comment at the code, because both look like they should change.

`Level.destroyBlock` calls `Block.dropResources(state, level, pos, blockEntity, breaker,
ItemStack.EMPTY)` — it never consults the breaker's hand. So leaves keep dropping saplings rather
than leaf blocks, and a bot does not litter a forest with item entities.

Nothing in this codebase calls `mineBlock`, which is the only thing that spends shears durability.
Otherwise a bot would snap a pair every 238 blocks and need somewhere to get another.

---

## Testing

Split along the line CLAUDE.md draws: `Items.SHEARS` resolves in a unit test, `new ItemStack(item)`
does not.

**Unit — `EquipmentTierTest`:**

1. `miningTools()` is `tools()` followed by shears, for all seven tool-bearing tiers. Shears go
   last. Order does not change what `optimalTool` picks — it keeps the strictly-faster tool — but
   it decides ties, and a tie is real: a wooden axe and shears both score 2.0 on glow lichen and
   vine, and the axe holds the slot only because it is iterated first.
2. `miningTools()` is empty for `NONE`, `LEATHER` and `CHAIN`.
3. `tools()` is still exactly three everywhere and `IRON` is still `LegacyItems` verbatim. The
   existing pins, unchanged — which is the entire point of splitting the accessor.

**GameTest — `BotActionTests`:**

4. `a_bot_shears_leaves` — `preBreak` on oak leaves, assert the main hand is `Items.SHEARS`.
5. `shears_break_leaves_in_eight_ticks` — the existing tick-counting loop against
   `assertValueEqual(ticks, 8)`. Derived, not measured then pasted: `BREAK_COST` 120 ÷
   `max(1, round(15.0 × 2))` is 4 runs, × `BREAK_PERIOD` 2.
6. `shears_never_displace_a_pickaxe` — `preBreak` on stone across tiers, assert the tier's own
   pickaxe. This guards the only real risk in the change.

Tests 4 and 6 assert on `bot.getMainHandItem()` after a direct `preBreak`, because `optimalTool` is
package-private in `agent.legacy` and the tests are in `gametest`. That is how the three existing
tool-choice tests already do it, and per the GameTest conventions a direct call beats ticking and
waiting.

`miner(...)` hardcodes `Blocks.STONE`. It gains a six-arg overload taking the block; the five-arg one
delegates with stone, so its five existing call sites do not move.

---

## Docs

- **README** — the `tools` row of the tier table and the "Tool tiers, per bot" bullet say shears come
  with every tier.
- **CLAUDE.md** — one line under *26.2 API notes*: shears' speeds are a `Tool` component from
  `ShearsItem.createToolProperties`, keyed on `shears_extreme/major/minor_breaking_speed`, not a
  `ToolMaterial` and not a `mineable/*` tag.

---

## Deviations to register

Appended to Plan B's numbered list, which every plan extends, continuing from 31:

- Every bot with tools carries shears on top of its tier's three. Upstream's `LegacyItems` had no
  fourth tool and no untiered one; shears are the only item a bot holds that is not chosen by tier.
  They change the tool choice on 28 blocks — leaves, wool and cobweb — and on nothing else.

---

## Backlog

**Break speed ignores hardness.** `blockBreakEffect` reads the tool's destroy speed and not
`BlockState.getDestroySpeed()`, so every block costs the same 120 progress. Glass, candles, beds,
carpets and heads are 115 blocks of that on their own, and 60 more join them — all of them six
seconds a block, where a player breaks glass instantly. This is upstream's behaviour and deviation
26 sanctions it.

The audit puts numbers on it for the first time, and they do not all point the same way. **175
blocks** stop being uniformly 120 ticks, but that is not 175 blocks getting faster: glass is
hardness 0.3 and would become near-instant, while reinforced deepslate at 55.0 and trial spawners
and vaults at 50.0 would become far slower than they are now. And **every block in the game** would
change timing, including stone — which `STAGE_COST` is defined to keep at exactly twenty ticks for
iron, and which `iron_still_breaks_a_block_in_twenty_ticks` pins by name.

That anchor is what makes the current speed model a documented extension of upstream rather than a
drift away from it, so replacing it is a deliberate redesign with winners and losers, not a tuning
change.
