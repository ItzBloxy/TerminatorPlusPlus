# Backlog

What is not built, and why. Two kinds of thing: **port work** the plans deliberately deferred, and
**new features** that upstream never had.

Nothing here is a defect. Everything ported so far is faithful to `master` or documented as a
divergence in Plan B's deviation register.

---

## Port work still outstanding

Ordered by how much they unblock, not by size.

### The public API module

`Terminator`, `BotManager`, `TerminatorPlusAPI`, `InternalBridge`, `AIManager`. Spec §4.4
postponed these deliberately so that real internal usage would shape the interface rather than
guesswork — **that usage now exists**, so this is the one with the strongest case for being next.
The agent works against the concrete `Bot` today.

### The neural-network AI

`IntelligenceAgent`, `NeuralNetwork`, `BotData`, `BotNode`, `NodeConnections`, `BotDataType`,
`ActivationType`, `BotAgent`, `BotSituation`, `VerticalDisplacement` — about 1,000 lines across ten
files, plus the `move` and `tickBot` branches that read them (Plan B correction 4) and the
`/tplus ai` command tree. The branches are already omitted in a way that leaves the seams visible.

### `Debugger`

497 lines. Nothing else depends on it.

### Narrowing `AgentState`

Twelve collections in one injected object was the deliberately mechanical choice (spec §4.3), taken
so sharing semantics could not change silently during the split. Distributing them to owners is now
safe: the collaborators exist and their tests pin the behaviour.

### Persistence for the environment lists

Neither the solid-block list nor the custom mob list survives a restart, which is upstream's
behaviour and is kept (Plan C decision 6). If it is ever wanted it is a config file and a
`ServerStartedEvent` listener, not a change to either list.

---

## Features upstream never had

These came out of the first real play-test. Several are small; two are substantial.

### Bots have no self-preservation

`getHealth()` is read in exactly two places in the entire codebase: `Bot.regenerate()`, which adds
a flat `0.025` HP every tick unconditionally, and `/tplus info`. **Nothing branches on health.**
That is why bots never retreat, never disengage, and never eat or drink — they do not need food,
they regenerate passively, and they cannot react to being hurt because nothing reads it.

Adding real survival behaviour means a health-aware branch in `tickBot` and a flee vector in
`Navigation`. That is a genuine design change, not a translation.

Plan D's armour changes how long a bot lasts and not what it does, which is worth being clear
about: netherite buys time, and a bot still walks into the thing killing it.

### Bots cannot use most items

A bot never ticks item use — `startUsingItem` appears in the shield path and in `Archery`, and the
shield is inert because of it. Food, potions and shields all need the same missing piece: a
use-tick. Build it once and **three** features become possible.

It was four until bows shipped. Bows turned out not to need it: only the first step of
`BowItem.releaseUsing` reads the frozen `useItemRemaining` counter, and only to recover how long
the draw had been held — which the bot already knew, because it started the draw. The arrow is
spawned directly and the draw animation rides the same packet pair the shield sends. **That did
not unblock the other three**, and a working bow is not evidence they are close. Deviation 41.

### The rest of ranged combat

**Bows themselves are built** — deviations 38–43, designed in
`docs/superpowers/specs/2026-09-14-bow-and-ranged-combat-design.md`. A bot carries a bow in a slot
of its own and holds position to shoot instead of towering when the target has been aloft 40 ticks,
when enough squadmates are already towering, or when it is stuck. What is listed here is what that
work deliberately left out.

**`target_camping`** — the target is well above the bot and its Y has stopped rising, meaning it has
settled on a pillar rather than actively climbing. The only one of the four candidate rules needing
new per-target state: `btList` and `btCheck` sample the *bot's* own column, and nothing records a
target's Y history. Adding it costs a sampler, one `RangedContext` component and one `RangedRule`
entry, and reshapes nothing, because the decision is already a pure function of a record.

**`unreachable`** — a gap, ravine or lava lake between bot and target. Needs a reachability concept
the agent has not got; see **A pathfinding and goal visualiser** below. `bot_stuck` catches most of
the same situations a few seconds later, for one map lookup.

**`target_fleeing`** — distance trending upward. Needs the same kind of history `target_camping`
does.

**A retreat, or a preferred-range band.** The most convincing archer, and the only option that
stops a bot walking into what it is shooting. It needs a flee vector in `Navigation`, which is the
same genuine design change **Bots have no self-preservation** above describes — so these two are
one piece of work, not two.

### A pathfinding and goal visualiser

Worth saying plainly: **there is no path to visualise.** The agent has no pathfinder, no graph and
no cost function. It normalises a vector at its target, jumps, and mines whatever is in front of
it. That is why bots prefer straight lines, and why one below you will mine straight down to your
level and then across rather than cutting the diagonal.

The descent half of that is now bounded — `/tplus descendrange` stops a stuck bot digging down
until it is within 8 blocks horizontally — but bounding a straight line is not the same as having
a path. There is still no graph, no cost function and no diagonal.

What a step costs is now measured, which it never was before. A bot tunnelling with netherite
tools spent about 47 ticks per block of forward progress, of which only ~30 was mining — the rest
was the jump arc, and `tickBot`'s decision branch sits behind `isBotOnGround()`, so that time was
idle rather than merely slow. Bots now walk under a low ceiling instead, which measured 39.3
ticks per block. In a tight corridor the difference is far starker than 17%: a jumping bot
bounces off the ceiling and barely mines at all, 2 blocks against walking's 17 over 700 ticks.

That is a constant-factor win on a straight line and still not a path. It also says something
about where the remaining time goes — movement costs 9.3 ticks per block, not the 3-5 a sustained
walk would, because `move` is only reached once a block has finished breaking. Making a bot walk
*while* it mines is the next constant factor, and a larger change than this one was.

A visualiser is therefore only interesting alongside actual pathfinding. The cheap version that
*would* help today is rendering the current `ScanOffset` decision and the target — a debug overlay
of "what did the scan pick this tick".

### MLG is not a movement option

The water clutch lives in `onFallDamage`, which runs only after the game has decided a bot is
falling too fast. It is a damage response, so it can never be *chosen* as a route — a bot will
never deliberately drop to you and clutch. Making it a movement option means a descent branch that
knows the clutch exists.

### Bots suffocate each other

`BlockScan.placeFinal` calls `setBlockAndUpdate` with no entity-occupancy check, which is upstream's
behaviour. Several bots towering in one column will seal each other in. A check against entities in
the target block would fix it and would be a deliberate divergence worth registering.

The `tower_quota` rule reduces how many bots reach one column — past the quota the rest shoot
instead of climbing — but it is a mitigation, not a fix. `placeFinal` still has no check.

### Break speed ignores hardness

`Mining.blockBreakEffect` reads the held tool's destroy speed and never
`BlockState.getDestroySpeed()`, so every block costs the same 120 progress — obsidian costs what
dirt costs, and glass costs what obsidian costs. Upstream's behaviour, sanctioned as deviation 26.

The shears audit put numbers on it for the first time. 221 blocks are mined at 1.0 because no tool
covers them. Shears fixed 28 of those and a hoe would fix 18 more, which leaves **175 that no
vanilla tool can reach**: glass and panes ×35, candles and candle cakes ×34, beds ×16, carpets ×16,
heads and skulls ×14, and 60 others.

Reading hardness is the only thing that moves them, and it is a redesign rather than a tuning
change. It would not simply make 175 blocks faster: glass at hardness 0.3 becomes near-instant,
while reinforced deepslate at 55.0 and trial spawners and vaults at 50.0 become far slower than the
flat 120 ticks they cost now. And it changes the time of **every** block in the game, including the
stone that `Mining.STAGE_COST` is defined to keep at exactly twenty ticks for iron, and that
`iron_still_breaks_a_block_in_twenty_ticks` pins by name. That anchor is what makes the current
speed model a documented extension of upstream rather than a drift away from it.

A hoe is the other half of the audit and is deliberately not built: 18 blocks, all of them player
builds or one biome, and shears already beat a netherite hoe on leaves 15.0 to 9.0.

The full audit is in `docs/superpowers/specs/2026-09-13-shears-and-tool-coverage-design.md`.

### `enemytarget generic` cannot check that a type is targetable

`/tplus enemytarget specific` filters its selector's results through `Targeting.isTargetable` and
tells the operator what it dropped. `generic` cannot do the same, because `isAttackable()` and
`isPickable()` are instance state and a generic target names a type that may have no instances yet.
So `generic minecraft:item` is accepted and then refused by the gate every tick, which is the
"successful command that does nothing" failure the empty-tag guard beside it exists to prevent.

Fixing it means a type-level table of what is targetable, maintained against every Minecraft
release and still wrong for modded entities. The live re-check is why it has not been built.

Two vanilla types would slip through such a table anyway: `minecraft:tnt` and
`minecraft:interaction` are pickable and attackable but declare `hurtServer` final returning false,
so they pass the gate at scan time too. See deviation 36.
