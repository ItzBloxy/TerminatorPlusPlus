# Loadouts and Entity Targeting — design

Two features upstream never had, both taken from the first real play-test. Neither is a translation,
so neither is covered by the port spec
(`2026-09-12-terminatorplus-neoforge-port-design.md`); this document is what stands in for `master`
when there is no `master` to check against.

**Branch:** `neoforge-port`. **Stack:** Java 25, NeoForge 26.2.0.87, Minecraft 26.2, Brigadier,
JUnit 6.1.3, NeoForge `testframework`.

**Backlog items closed:** *Loadouts* (in full) and *Targeting a specific entity* (in full). Both are
in `docs/backlog.md` under "Features upstream never had".

---

## Why these two

Arming a squad currently costs three commands and a fixed order:

```
/tplus create Hunter 5
/tplus armor diamond
/tplus give minecraft:diamond_sword
```

Both equipment commands apply to **every bot that exists**, so a second squad cannot differ from the
first, and neither can be configured before it spawns. That is the whole of the "can't set a default
before creating" and "only one default item" complaints.

Targeting has the mirror-image gap. `/tplus playertarget` names one player; `CUSTOM_MOB_LIST` names
types; nothing names *that* entity. There is no way to say "kill the ender dragon".

---

## Scope

**In:**

- Armour, tools and a held item as arguments to `/tplus create`.
- A per-bot tool tier, replacing `Mining`'s hardcoded iron.
- `/tplus tools <tier>` for bots that already exist.
- `/tplus enemytarget generic|specific`, a new `TargetGoal.ENTITY`, and per-bot enemy-target state.
- Both targeting commands set the goal themselves.

**Out:**

- Everything the port has not reached: the neural AI, `Debugger`, the public API module, narrowing
  `AgentState`. Unchanged from the port spec §4.4.
- Persistence. Nothing here survives a restart, matching every other list in this mod.
- Enchantments, potions, off-hand, shields, inventory contents. `EquipmentTier` is four armour
  pieces and three tools, and adding more is a change to one enum.
- Per-bot *goals*. The goal stays one global setting on `Targeting`; only the target is per-bot.

---

## Feature 1 — equipment at spawn

### The command shape

Brigadier has no optional-in-the-middle argument. A chain grows left to right and each node either
executes or continues, so reaching a later argument means typing the earlier ones. The chain is six
nodes deep and `none` is the filler:

```
/tplus create <name>
/tplus create <name> <count>
/tplus create <name> <count> <playerlist|none>
/tplus create <name> <count> <playerlist|none> <armor>
/tplus create <name> <count> <playerlist|none> <armor> <tools>
/tplus create <name> <count> <playerlist|none> <armor> <tools> <item>
```

```
/tplus create Hunter 5 none netherite diamond minecraft:bow
/tplus create Hunter 5 playerlist diamond
/tplus create Hunter 5 none none none minecraft:trident
```

`<item>` is last because it is the only argument with no sensible filler — an `ItemArgument` cannot
express "nothing", and not typing it is the same thing.

`playerlist` stops being a literal and becomes a `StringArgumentType.word()` slot suggesting exactly
`playerlist` and `none`, rejecting anything else in the handler. It keeps the same spelling and the
same effect in the same position, so `create Hunter 5 playerlist` is unaffected, but it now occupies
a fixed depth instead of hanging off every node. That costs one
form: **`create Hunter playerlist`, with the count omitted, no longer parses.** It is
`create Hunter 1 playerlist`.

Three rejected alternatives, and why:

| | Why not |
|---|---|
| Keyword pairs (`armor diamond tools netherite`), order-free | Needs a Brigadier redirect loop, and a redirect opens a **fresh `CommandContext`**. The executor after the loop cannot see `name` or `count`, and `CommandContext` has `getChild()` but no `getParent()`, so there is no way back up. Vanilla solves this by mutating the source (`/execute`); we have nothing to mutate |
| A full permutation tree, order-free without redirect | Four optional arguments is 24 paths. Generated, not written, but still 24 paths in the help output |
| Named loadouts (`/tplus loadout raider armor diamond`, then `create Hunter 5 raider`) | A second command family, which is the thing this feature exists to remove. Worth revisiting only if loadouts ever need to persist |

### `EquipmentTier`

A new enum in `net.nuggetmc.tplus.bot`, holding two arrays per tier:

- `Item[] armor` — four pieces in `EquipmentSlot` order FEET, LEGS, CHEST, HEAD, matching the
  existing `ARMOR_SLOTS` constant it replaces.
- `Item[] tools` — three in `Mining.TOOLS` order: pickaxe, axe, shovel.

Either array may be empty, because vanilla tiers are not symmetric. A tick means the tier is
**accepted in that slot**, so `none` ticks both — it is the tier that strips the slot, and both its
arrays are empty:

| | none | leather | chain | wood | stone | copper | gold | iron | diamond | netherite |
|---|---|---|---|---|---|---|---|---|---|---|
| armour | ✓ | ✓ | ✓ | — | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| tools | ✓ | — | — | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

`copper` is new in 26.2 and complete on both halves; `stone` and `leather` were not in the original
request but exist, cost one line each and are what an operator will try.

The asymmetry is the reason each argument slot gets its own suggestion list and its own rejection:
`create X 1 none wood diamond` must say *"wood has no armour; armour tiers are: …"*, not silently
leave the bot bare. The two names come back from `armorTiers()` and `toolTiers()`, so the message
and the completions cannot drift apart.

This moves `ARMOR_TIERS` and `ARMOR_SLOTS` out of `BotCommands`, which is 886 lines and growing by
two commands in this document.

**Constructing an `ItemStack` in a static initialiser throws "Components not bound yet" and breaks
mod loading outright.** The enum holds `Item` constants — registry objects, safe at class-init, as
the existing `ARMOR_TIERS` map already proves — and stacks are built at the call site.

### Per-bot tools

`Mining.TOOLS` is a `private static final List<Item>` of one iron pickaxe, axe and shovel:
upstream's `LegacyItems`, deliberately kept so a bot never holds a diamond pickaxe it did not earn.

It has exactly one reader, `optimalTool(BlockState)`, called from one place — `Mining.preBreak`,
where `bot` is already in scope. So:

```java
bot.setItem(optimalTool(bot.getToolTier(), target));
```

`Bot` gains `EquipmentTier toolTier`, defaulting to `IRON`. A bot nobody configured mines exactly as
it does today, which is what keeps this a divergence in capability rather than in behaviour.

### `/tplus tools <tier>`

Symmetric with the existing `/tplus armor <tier>`, applying to every live bot. Without it, tools
would be the only one of the three properties that can be set at spawn and never changed.

---

## Feature 2 — `/tplus enemytarget`

```
/tplus enemytarget                          report what is set
/tplus enemytarget clear                    clear it, leaving the goal alone
/tplus enemytarget generic <type-or-tag>    live, by type
/tplus enemytarget specific <targets>       fixed, by entity
```

`clear` empties the target but does **not** put the goal back. The setters change the goal because
the operator asked for a target and there is one right answer; on `clear` there is no right answer,
and re-aiming every bot at the nearest player as a side effect of clearing is worse than leaving a
goal that finds nothing until `/tplus goal` says otherwise.

The two modes answer one question — *what happens to a zombie that spawns a minute from now* — and
they answer it opposite ways, so the operator states which they meant rather than having it inferred
from how many entities happened to be standing around.

### `generic` — live, by type

Takes `ResourceOrTagArgument.resourceOrTag(buildContext, Registries.ENTITY_TYPE)`, so
**`#minecraft:raiders` is accepted as readily as `zombie`**. That is how several types are named
without inventing an add/remove/list family alongside the one `environment` already has.

`Result.unwrap()` returns `Either<Holder.Reference<EntityType<?>>, HolderSet.Named<EntityType<?>>>`,
so the tag is expanded to concrete types **at command time**. An expanded set can be reported and
unit tested; a stored `Predicate` can be neither. The cost is that a datapack reload changing a tag
does not reach an already-set target, which is the same staleness every other list here has.

`Result.asPrintable()` gives `minecraft:zombie` or `#minecraft:raiders` for the feedback message
without reconstructing either.

### `specific` — fixed, by entity

Takes `EntityArgument.entities()`, resolves it once, and keeps the UUIDs. A selector that matched
seven zombies pins those seven; `limit=1` and a bare UUID are the same mechanism with a set of one.

`@e` matches boats and item frames, and `locateTarget` returns `LivingEntity`. The selection is
filtered to `LivingEntity` at command time and the feedback says how many were dropped, because
failing silently here looks identical to the command not working.

### `EnemyTarget`

An immutable record in `net.nuggetmc.tplus.bot`:

```java
public record EnemyTarget(Set<EntityType<?>> types, Set<UUID> ids, String label) {
    public boolean matches(Entity entity) {
        return types.contains(entity.getType()) || ids.contains(entity.getUUID());
    }
}
```

`matches` is a pure set test over two sets, so the whole targeting rule is unit testable with no
world, no level and no bot.

`label` is what `/tplus enemytarget` prints back. For `generic` it is `Result.asPrintable()` —
`minecraft:zombie` or `#minecraft:raiders`. For `specific` there is no equivalent: a resolved
selector cannot be spelled back out, so the label is the count and the distinct types in it, e.g.
`7 entities (zombie)`. `EnemyTarget.NONE` is the empty record and labels itself `nothing`.

Held **per bot**, like `targetPlayer`, which is the command this one is modelled on. Because the
record is immutable, every bot holds the same reference and the per-bot form costs one field and
nothing else, while leaving "these three bots hunt the dragon, those two hunt you" open.

Each mode replaces the whole target rather than merging into it. `generic` sets `types` and empties
`ids`; `specific` does the reverse. The command reads `<generic|specific>`, so a state where both
halves are populated has no command that produces it and no sentence that describes it.

### `TargetGoal.ENTITY`

One new constant. `Targeting.locateTarget` gains a case that scans `livingEntities(level)` and keeps
whatever passes `target.matches(entity)` and the existing `validateCloserEntity`:

- **Skip the bot itself.** Without `bot != entity`, `generic player` makes every bot target itself.
  The `NEAREST_BOT` branches already carry this guard; the mob branches do not need it.
- **Other bots are not skipped.** Bots are `ServerPlayer`s, so `generic player` reaching them is
  correct, and `enemytarget specific @e[type=player]` naming one is the point.
- **A dead or unloaded target yields no target at all.** `validateCloserEntity` already checks
  `isAlive()` and same-level, so nothing needs adding and nothing falls back.

That last point is worth stating precisely, because the enum lies about it elsewhere: `PLAYER`'s
description reads *"Defaults to NEAREST_VULNERABLE_PLAYER if no player found"* and the code does no
such thing — it returns null. `ENTITY`'s description will describe what it does.

`ServerLevel.getEntity(UUID)` is `getEntityInAnyDimension(UUID)` in 26.2, and searching every
dimension is harmless here because `validateCloserEntity` rejects a cross-level target anyway.

### Why this does not reuse `CUSTOM_MOB_LIST`

It looks like it should. `generic` is a set of entity types and `CUSTOM_MOB_LIST` is a set of entity
types, and Plan C just built the commands that fill it. Two concrete reasons not to:

1. **`CUSTOM_LIST` only matches when `customListMode == CUSTOM`.** An operator who had run
   `environment moblisttype hostile` would type `enemytarget generic zombie`, get a success message,
   and watch nothing happen — the exact failure mode Plan C existed to remove.
2. **Three other goals read that list.** `NEAREST_HOSTILE`, `NEAREST_RAIDER` and `NEAREST_MOB` each
   consult it under their own mode. Writing it from a targeting command changes all three
   invisibly.

The two features stay legible as long as they stay separate: `environment` curates a list that
modifies existing goals, `enemytarget` sets a target for a goal of its own.

### Both targeting commands set the goal

`/tplus playertarget` currently ends with *"Set the goal to 'player' for this to take effect"* — a
faithful port of upstream's nag. `enemytarget` sets `ENTITY`, `playertarget` sets `PLAYER`, and both
say so:

```
> /tplus enemytarget generic #minecraft:raiders
Now hunting #minecraft:raiders (6 types). Goal set to ENTITY.

> /tplus playertarget ItzBloxy
All bots now target ItzBloxy. Goal set to PLAYER.
```

Changing `playertarget` is a divergence on already-ported code and is registered as one. It is worth
it: two adjacent commands that differ on whether they finish the job are worse than one command that
differs from upstream.

---

## Error handling

Everything that can be pushed into an argument type is, following Plan C decision 4 — an unknown
item, entity type or tag is rejected by Brigadier before any handler runs, with vanilla's message.
What is left:

| Case | Response |
|---|---|
| A tools tier in the armour slot, or the reverse | Failure naming the valid tiers for *that* slot |
| `specific` matching only non-living entities | Failure: nothing to target, with the count seen |
| `specific` matching some non-living entities | Success, saying how many were ignored |
| `generic` on a tag that is empty | Failure. An empty target is silently inert, which is the thing this design keeps avoiding |
| `enemytarget` or `tools` with no bots loaded | Success with a count of zero, matching `armor` and `give` |
| Any goal command without `LegacyAgent` | The existing "No legacy agent is installed" |

---

## Testing

Per the tiers in `CLAUDE.md`, and the rule that a green suite is not "it works":

**Unit** (`src/test`, no world): `EquipmentTier` lookup by name, the armour/tools partition, and
that every non-`NONE` tier's populated array has no nulls and the right length. `EnemyTarget.matches`
across empty, types-only, ids-only, and a non-match.

**GameTest** (`src/gametest`): a bot created with a tier wears it in the right four slots;
`optimalTool` picks from the bot's tier and not from iron; the `ENTITY` goal finds a generic-type
match, ignores a mob that is not in the set, finds a specific target by UUID, and returns null once
that target is dead.

Every one of these is a **direct call**, not a tick-and-wait. `move()` adds `Math.random()` to every
jump, and three tests in Plan B passed, failed, then passed again on an unchanged build before that
rule existed.

`Targeting.customListMode` and `CUSTOM_MOB_LIST` are static and shared across the suite's one JVM.
Any test touching them restores them in a `finally`.

**A client pass.** Four bugs in this port were invisible to 132 GameTests and to RCON. Armour is
rendering, and rendering is the tier nothing else covers.

---

## Deviations to register

Appended to Plan B's numbered list, which every plan extends, continuing from 21:

- `/tplus create` takes armour, tools and a held item. Upstream had no equipment at spawn at all.
- `playerlist` becomes an argument rather than a literal, and `create <name> playerlist` with the
  count omitted no longer parses.
- Tools are per-bot and tiered. Upstream's `LegacyItems` was one hardcoded iron set; the default is
  still that set.
- `/tplus tools` is new. Upstream had no equivalent.
- `TargetGoal.ENTITY` is a new constant on an enum otherwise ported verbatim from
  `EnumTargetGoal`.
- `/tplus enemytarget` is new. Upstream had no per-entity target.
- `/tplus playertarget` now sets the goal instead of telling the operator to.
