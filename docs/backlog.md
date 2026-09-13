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

### Bots cannot use items

A bot never ticks item use — `startUsingItem` appears once, in the shield path, which is why the
shield is inert and documented as such. Food, potions, bows and shields all need the same missing
piece: a use-tick. Build it once and four features become possible.

### Ranged attacks

`LegacyAgent.attack` is melee-only. A bow needs the use-tick above, plus a ranged branch in the
attack decision and something to say when a bot prefers distance to closing.

Plan D closed half of this without meaning to: `/tplus create Archer 3 none none none minecraft:bow`
arms a squad with bows at spawn. They hold them and hit people with them. The missing piece is
still the use-tick.

### A pathfinding and goal visualiser

Worth saying plainly: **there is no path to visualise.** The agent has no pathfinder, no graph and
no cost function. It normalises a vector at its target, jumps, and mines whatever is in front of
it. That is why bots prefer straight lines, and why one below you will mine straight down to your
level and then across rather than cutting the diagonal.

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
