# Bows and ranged combat — design

`LegacyAgent.attack` is melee-only, and `Navigation.checkUp` answers every out-of-reach target the
same way: build a pillar of cobblestone and climb. Against a Ghast that achieves nothing, against
the Ender Dragon it is absurd, and against twenty bots at one target it is a suffocation machine.

This gives a bot a bow, and gives the agent a second answer to "I can see it and I cannot walk to
it."

Register the changes as deviations 38–43.

---

## The backlog is wrong about the blocker

`docs/backlog.md` files bows under **Bots cannot use items**, alongside food, potions and the
shield, and says all four wait on one missing piece — a use-tick:

> A bot never ticks item use — `startUsingItem` appears once, in the shield path, which is why the
> shield is inert and documented as such.

That is true of the shield and false of the bow. The shield is inert because vanilla's
`getItemBlockingWith` requires `blockDelayTicks` to have elapsed, measured from `useItemRemaining`,
which is only decremented by `updateUsingItem`, which is only reached from `LivingEntity.tick()`,
which never runs for a bot. `Bot.isBotBlocking` documents the whole chain. Nothing can raise a
shield without ticking item use.

A bow has a way around it, because `BowItem.releaseUsing` is only three steps and the third is
public:

```java
int timeHeld = this.getUseDuration(itemStack, entity) - remainingTime;   // needs the counter
float pow = getPowerForTime(timeHeld);                                   // arithmetic
List<ItemStack> firedProjectiles = draw(itemStack, projectile, player);  // needs an inventory
this.shoot(serverLevel, player, hand, itemStack, firedProjectiles, pow * 3.0F, 1.0F, pow == 1.0F, null);
```

Only the first step reads the frozen counter, and only to turn it into a number we already know —
the bot decided when to start drawing, so it knows how long it has drawn. Skip the method and spawn
the arrow the way `Projectile.spawnProjectileFromRotation` does:

```java
Arrow arrow = new Arrow(level, bot, new ItemStack(Items.ARROW), bowStack);
arrow.shootFromRotation(bot, bot.getXRot(), bot.getYRot(), 0f, power * 3f, uncertainty);
level.addFreshEntity(arrow);
```

**This project already proves that path works.** `an_arrow_hurts_a_bot` in `BotCombatTests`
constructs an `Arrow`, calls `shoot`, adds it to the level and asserts the damage lands.

The draw animation comes free as well. Clients render another player's bow pull from the
living-entity-flags byte and count draw ticks themselves — which is the pair the shield path
already sends, `startUsingItem` followed by a `ClientboundSetEntityDataPacket` broadcast. A bot can
therefore visibly draw while its server-side counter stays frozen at zero.

**Bows do not deliver the use-tick, and the use-tick is not a prerequisite for bows.** Food,
potions and the shield are exactly as blocked after this work as before it. Deviation 41 records
that so nobody later reads a working bow as evidence the other three are close.

## What upstream did

Nothing. `LegacyAgent.attack` is melee in both codebases and upstream's neural network had no
ranged output. Plan D closed half of this by accident — `/tplus create Archer 3 none none none
minecraft:bow` arms a squad at spawn — but the README's claim that "they hold them and hit people
with them" deserves scrutiny: `ItemUtils.getLegacyAttackDamage` is the 1.8 melee table, it has no
entry for a bow, and the fallback is `FIST = 0.25`. Those bots punch for a quarter heart, forever.

So this is new behaviour throughout, designed rather than translated, and the
`paper-original` branch is not an oracle for any of it.

---

## Architecture

Five new pieces. Three of them never touch a `Level`.

| Piece | Package | Responsibility |
|---|---|---|
| `RangedContext` | `bot.ranged` | A record of scalars sampled from the world for one bot on one tick |
| `RangedDecision` | `bot.ranged` | `decide(RangedContext)` → the mode and the rule that produced it |
| `RangedRule` | `bot.ranged` | The named reasons — both the rules that produce RANGED and the gate failures that produce MELEE — for `/tplus info` and for tests |
| `BowBallistics` | `bot.ranged` | `solve(origin, aimPoint, targetVelocity, power)` → a pitch and yaw |
| `Archery` | `agent.legacy` | The collaborator: samples the context, runs the decision, owns the draw state machine and the hand |

Plus one field on `Bot`: **the bow slot**, beside `defaultItem`, with a getter and setter. It needs
to live there rather than in `Archery` because four things outside the agent touch it —
`BotFactory`/`create` set it at spawn, `/tplus bow` writes it, `/tplus info` prints it, and the
"`defaultItem` is itself a bow" shortcut resolves against it.

`RangedDecision` and `BowBallistics` are pure. That is deliberate and follows the rule CLAUDE.md
states plainly: `EnemyTarget.matches` takes an `EntityType` and a `UUID` rather than an `Entity` so
that the whole targeting rule can be unit tested, and these do the same. Every rule, every gate,
the hysteresis and every trajectory therefore test at the pure tier in seconds, and the GameTests
are left with one job — proving the context is sampled correctly.

One trap that shapes the record: `Items.BOW` resolves at the unit tier but `new ItemStack(Items.BOW)`
throws "Components not bound yet". `RangedContext` carries a `boolean hasBow`, never an `ItemStack`.

### State lives in `Archery`

Not in `AgentState`. The backlog asks for those twelve collections to be **narrowed**, and says the
narrowing is now safe because the collaborators exist and their tests pin the behaviour. New state
is the easy case: nothing outside `Archery` reads it, so there is no sharing semantic to guess at.
`Archery` hooks the same per-bot removal path `AgentState.forget` uses, and clears itself from
`stopAllTasks`. Deviation 43.

### Where it slots into `tickBot`

CLAUDE.md: *"The ordering inside `tickBot` is the specification for the rest of the port. Every
terrain check returns 'handled, stop here', so moving one changes behaviour."* This adds **two**
touch points — the branch itself, and a reset on the path that never reaches it:

```
targeting.locateTarget
if (livingTarget == null) { mining.stopMining(bot); >>> archery.reset(bot); return; }
blockScan.tryPreMLG          ← a falling bot saves itself first, always
blockScan.clutch
fallDamageCheck
behaviors.miscellaneousChecks ← fire, lava and water hazards win
attack (melee)                ← inside 4 blocks a bot still punches
>>> archery.tick(...)         ← returns handled → movement is suppressed
waterGround / isBotOnGround / navigation
```

Everything above the insertion is either safety or the existing melee path, and everything below is
movement. That placement *is* the hold-position decision — it is expressed as ordering rather than
as a flag, which is the shape the rest of this class already has.

**The reset is not optional and is easy to miss.** `tickBot` returns at line 138 when there is no
target, *above* the insertion point, so a bot that is mid-draw when its target dies, teleports away
or is filtered out by the goal never reaches `archery.tick` again. Without the reset it holds a
drawn bow, in a `DRAWING` state that never advances, until something else happens to it. The
existing `mining.stopMining(bot)` on that same line exists for exactly this reason, and the reset
goes beside it.

### Hand contention needs two mechanisms, not none

`setItem(null)`, which restores `defaultItem`, is called from `Navigation.move` and
`BotBehaviors.resetHand`, and both sit below the return — so while a bot is RANGED nothing fights
the bow out of its hand, and the first `resetHand` after flipping back to MELEE restores the sword
by itself. That much is free. Two things above the return are not:

- **`boatCooldown` must be honoured.** `BotBehaviors.boatOverLava`, reached from
  `miscellaneousChecks`, puts an `OAK_BOAT` in the bot's hand and marks `state.boatCooldown`.
  `resetHand` already has an early return for that set — its comment reads "leaves the boat in its
  hand" — and `Archery` must take the same early return, or a bot crossing a lava lake swaps the
  boat for a bow mid-crossing.
- **`mining.stopMining(bot)` on entering RANGED.** The melee branch is guarded by
  `!state.miningAnim.containsKey(bot)`, but nothing guards the ranged one — and `BOT_STUCK` fires
  *precisely* when a bot is mining and getting nowhere, so a mining bot entering RANGED is the
  common case rather than an edge one. Both `Navigation.tower` and `resetHand` call `stopMining`
  before taking the hand; this does the same. `state.noFace` is then moot, because it is only
  populated on the mining path.

### The draw runs in `tickBot`, not on the scheduler

The shield schedules its release with `scheduler.runLater`. A bow cannot: a scheduled release cannot
re-aim, and a Ghast drifts. `Archery` holds a small per-bot state machine ticked every tick:

```
IDLE ──(mode is RANGED, all gates pass)──▶ DRAWING(sinceTick)
DRAWING ──(every tick)──▶ re-aim at the target
DRAWING ──(sinceTick ≥ DRAW_TICKS, release gates pass)──▶ release ▶ COOLDOWN(RELEASE_COOLDOWN)
any ──(mode flips to MELEE)──▶ IDLE, stopUsingItem, hand restored
```

Entering `DRAWING` sets the bow and calls `startUsingItem(MAIN_HAND)` with the entity-data
broadcast. Releasing calls `stopUsingItem` with the same broadcast, spawns the arrow and plays
`SoundEvents.ARROW_SHOOT`.

`DRAW_TICKS` is **20**, which is `BowItem.MAX_DRAW_DURATION` — a full draw, so
`getPowerForTime(20)` gives 1.0, velocity `1.0 × 3.0F`, and a critical arrow, matching the
`pow == 1.0F` argument `BowItem.releaseUsing` passes. `RELEASE_COOLDOWN` is **10**, making a
30-tick shot cycle, about a real player's sustained rate.

---

## The decision

### Gates

All of these must hold before any rule is consulted. Failing any one means MELEE.

| Gate | Source |
|---|---|
| The bot has a bow | The bow slot, or `defaultItem` is a `BowItem` |
| Target is not invincible | `PlayerUtils.isInvincible(gameMode)` — the same call the melee gate makes |
| `distance ≥ 4` | The melee floor, and the exact complement of the melee gate — `LegacyAgent.attack`'s third condition is `distanceTo(target) >= 4` → *skip*, so melee fires below 4 and ranged at or above it. Writing this `> 4` would leave a hairline gap at exactly 4.0 where a bot does neither |
| `distance ≤ MAX_RANGE` | New. Default **24**, not 40 — see the cost note below |
| Line of sight | Two rays, as melee does, but **not** `checkFreeSpace` verbatim — see below |
| The bot is on the ground | `isBotOnGround()`. An airborne bot cannot aim, and hold-position means it will be grounded anyway |
| The bot is not crowded | Fewer than `CROWD_LIMIT` other bots within `CROWD_RADIUS`. Defaults 4 and 4.0 |

### Line of sight cannot be `checkFreeSpace` verbatim

The first draft of this spec said the melee check could be reused unchanged. It cannot, for two
independent reasons, and both were found by reading it rather than by assuming it.

**It is priced for four blocks, not forty.** `LegacyUtils.checkFreeSpace` samples **32 points per
block of distance** — `j = floor(length * 32)`, one `BlockPos.containing` and one `getBlockState`
per step. Melee pays 128 lookups because it is bounded by the same 4-block gate that calls it, and
it pays them only on `tickDelay(3)`. A 40-block ray is 1,280 lookups, doubled when the first ray
fails and the second runs, every tick, per bot. A hundred bots is on the order of a quarter of a
million block lookups per tick. Three changes bring it back in budget:

- `MAX_RANGE` defaults to **24**, not 40. That is comfortably inside a bow's useful range and cuts
  the worst-case ray by 40%.
- The ranged check samples **4 points per block**, not 32. A step of 0.25 blocks still cannot pass
  through any full block, so nothing that blocks a bot blocks less reliably; only sub-block geometry
  and thin diagonal gaps read differently, and `checkFreeSpace` already documents that it is not a
  raycast and misses those anyway.
- It is evaluated on `tickDelay(3)`, matching melee's cadence, and the result is cached in the
  per-bot archery state between evaluations.

Together that is 96 lookups per ray every three ticks — 32 per tick — against 1,280 per tick today's
constants would give at 40 blocks. A **40× reduction**, and it lands slightly cheaper per bot than
the melee check it is modelled on, which pays 128 samples on the same 3-tick cadence.

**It disagrees with a projectile about what is empty.** `BlockRules.isAir` is a set-membership test,
not `state.isAir()`, and the set contains `WATER`, `LAVA`, `FIRE`, `SOUL_FIRE`, `SNOW`, vines, ferns,
grasses, seagrass, kelp and sunflower. That is correct for its actual job — it is a *movement*
predicate, and a bot can walk or swim through all of those. It is wrong for a projectile: an arrow
crossing water drops to `WATER_INERTIA = 0.6` and falls short, and one crossing lava catches fire.
The ranged check therefore treats water and lava as blocking while keeping the vegetation
exemptions, which is a deliberate divergence from the melee predicate rather than a copy of it.

Recorded as part of deviation 38.

### Two gates deserve their reasoning written down

**Crowding gates the mode, not the shot.** A bot squashed among hunters drops to MELEE and falls
through to navigation, so it pushes forward and de-crowds itself rather than standing in the scrum
doing nothing. This means crowding **overrides `TOWER_QUOTA`** when the two conflict, which is the
intended picture: in a twenty-bot swarm the packed middle charges and the bots squeezed out to the
edges — the ones with clear lines — shoot.

**The firing line gates only the release.** A squadmate wandering through the line is transient, and
flipping mode over it would flicker the bot in and out of navigation. A bot with a blocked line
holds position, holds fire, and looses as soon as the line clears. It is a shot filter, not a mode
input, so it is checked at release and is not part of `RangedContext`.

Friendly fire is deliberately left **on**. Bot arrows are owned by a `Player`, so they are gated by
`canHarmPlayer`, which says yes by default; no scoreboard team is created and nothing global is
mutated. The crowding gate and the firing-line check are the whole mitigation.

### Rules

Any one true, with every gate passing, means RANGED.

| Rule | Condition | Default |
|---|---|---|
| `TARGET_FLYING` | Target `!onGround()` for N consecutive ticks | N = 40 |
| `TOWER_QUOTA` | ≥ Q bots from `state.towerList` within R blocks of the target | Q = 3, R = 24 |
| `BOT_STUCK` | `state.btCheck.get(bot)` is true | — |

`TARGET_FLYING` is behavioural rather than a type list, and that is a decision rather than a
shortcut. 26.2 has no `FlyingMob` class to test against, and CLAUDE.md already makes the argument
against type tables for `enemytarget generic`: they go stale every release and are wrong for modded
entities. Off-the-ground-for-N covers Phantoms, Ghasts, the Ender Dragon, elytra players, creative
flight and anything a mod adds, with nothing to maintain. N = 40 because a player's jump arc is
about 12 ticks, so the margin is more than 3×.

**The counter is per bot and resets on target change.** `Targeting.locateTarget` runs every tick and
may return a different entity than it did last tick — a closer player, a newly-spawned mob — so an
aloft count carried across a switch would let a grounded target inherit a Phantom's 40 ticks and
flip the bot to RANGED against something standing on the floor. `Archery` stores the target's UUID
alongside the count and zeroes both when the UUID changes. Per bot rather than per target, because a
per-target map would need its own eviction for entities that die or unload, and the observation is
cheap enough to duplicate.

`TOWER_QUOTA` costs almost nothing to compute: `state.towerList` is already "which bots are towering
and where they started", `tickBot` already removes a bot from it once it climbs above its target,
and `registry.bots()` is the squad. It also mitigates the backlog's **Bots suffocate each other**
without fixing it — `BlockScan.placeFinal` still has no entity-occupancy check, and this only means
fewer bots reach the same column.

`BOT_STUCK` is the cheapest of the three and catches the most cases: `btCheck` already means
precisely "this bot has not left its block column in 20 ticks", and `tickBot` already reads it.
Walled in, wedged, or tunnelling toward something it will never reach — all of it, for one map
lookup.

### Hysteresis

**Hysteresis applies to rules going false, never to gates failing.** The distinction is the whole of
it, and getting it backwards would be a bug:

- **A gate fails → MELEE immediately.** Every gate describes a condition under which the bot
  *cannot usefully shoot at all*. Holding RANGED through a failed gate would mean standing still,
  not drawing, and not navigating either — strictly worse than any alternative. A bot that walks
  inside 4 blocks, loses line of sight, or gets crowded drops to MELEE on that tick.
- **A rule goes false while every gate still passes → hold RANGED for `MODE_MIN_TICKS` (40).**
  This is where oscillation actually lives: a target bouncing on and off the ground near
  `TARGET_FLYING`'s threshold, or a squadmate finishing its tower and dropping `TOWER_QUOTA` below
  Q for a few ticks. The bot keeps firing rather than flickering in and out of navigation.

Scoping it this way means the melee floor needs no special case — `distance ≥ 4` is a gate, so it
already flips instantly. A bot standing at point-blank holding a half-drawn bow while something
punches it is the single worst thing this feature could produce, and it falls out of the rule rather
than being patched around.

---

## Aiming

An arrow flies under gravity 0.05 (`AbstractArrow.getDefaultGravity`) with 0.99 drag per tick
(`AbstractArrow.INERTIA`), which has no closed-form solution. `BowBallistics` simulates instead:
step the arrow forward under the same two constants and binary-search the launch pitch until it
passes within tolerance of the aim point. Then one lead pass — re-solve against
`aimPoint + targetVelocity × flightTime`, where the flight time falls out of the first solve.

**The launch origin is not the bot's eye.** `AbstractArrow(type, mob, level, …)` delegates to
`mob.getX(), mob.getEyeY() - 0.1F, mob.getZ()`, so the solver must be given that point or its
answer is consistently biased. It matters less for the angle than for the tests — `BowBallistics` is
a pure function and its fixtures will encode whatever origin the spec names, so naming the wrong one
bakes the error into the suite.

Launch speed is exactly the power figure: `Projectile.getMovementToShoot` normalises the direction
and scales by `pow`, so a full draw leaves the bow at 3.0 blocks per tick.

About thirty lines, entirely pure, and unit-testable against known trajectories. The cheap
alternative is the Skeleton fudge — `AbstractSkeleton.performRangedAttack` adds
`distanceToTarget * 0.2F` to the Y delta — and it is rejected for two reasons. It is tuned for the
`1.6F` launch speed a skeleton uses and does not transfer to a bow's 3.0. And it does not lead a
moving target at all: integrating 3.0 blocks/tick against 0.99 drag puts a 20-block shot about
**7 ticks** in the air, in which a sprinting player covers roughly **2 blocks** — against a hitbox
0.6 wide. A bow that misses is not a feature.

### The aim point, and the dragon

The aim point is normally `target.getEyePosition()`. The Ender Dragon is the exception, and it is
worth spelling out because this project has already been caught once by a dragon-part assumption.

`Level.getEntities(except, bb, selector)` explicitly merges `dragonParts()` into its results, so an
arrow **does** hit the dragon with no special handling — it collides with whichever `EnderDragonPart`
is geometrically in the way. But `EnderDragon.hurt` opens with
`if (part != this.head) damage = damage / 4 + Math.min(damage, 1)`, which is the same quarter-damage
rule deviation 37 found for melee.

Melee fixed it by redirecting the *recipient* — `LegacyAgent.attack` passes `dragon.head` to
`bot.attack`. A projectile has no recipient to redirect; it hits what it flies into. So the fix has
to move into the **aim point**: when the target is an `EnderDragon`, `Archery` aims at
`dragon.head.position()`. That is deviation 37's reasoning applied to a projectile rather than a
duplicate of it, and it is why the aim point is a separate concept from the target in `Archery`
rather than an inlined `target.getEyePosition()` call.

This does not guarantee a head hit — a wing can still intercept an arrow aimed at the head — so it
is an improvement in expectation, not a correctness fix. Recorded as part of deviation 38.

---

## Ammunition and damage

**Arrows are infinite.** A fresh `new ItemStack(Items.ARROW)` per shot; the bot's inventory is never
read and `ProjectileWeaponItem.useAmmo` is never called. This is consistent with everything else a
bot conjures — cobblestone to tower, water buckets to clutch, boats over lava — and with tools that
never lose durability. Deviation 40.

Spawned arrows are set to `AbstractArrow.Pickup.DISALLOWED`, so twenty archers do not carpet the
ground with collectable items. It also keeps them out of the way of Plan D's non-living targeting,
where a grounded arrow is a plausible `enemytarget generic` result.

**Damage is vanilla's, not the 1.8 table.** `AbstractArrow.baseDamage` is 2.0, scaled by velocity,
which produces the normal full-draw figure. `ItemUtils.getLegacyAttackDamage` is deliberately not
consulted: it is a *melee* table, it has no bow entry, and its `FIST = 0.25` fallback is exactly the
bug that makes today's `/tplus create Archer` bots useless. Deviation 39.

## The `pvp` gamerule

26.2 moved `pvp` out of `server.properties` and into a gamerule — `GameRules.PVP`,
`registerBoolean("pvp", PLAYER, true)` — read per-level by `ServerLevel.isPvpAllowed()`. It gates
player-owned arrows at two sites:

- `AbstractArrow.canHitEntity` — the arrow passes straight through
- `ServerPlayer.hurtServer` — the damage is refused even if it somehow arrives

The existing melee path calls `hurtServer` with `damageSources().playerAttack(this)` directly and is
unaffected by any of it. So with `pvp` off, **melee bots keep killing players and archer bots
silently stop** — the "successful command that does nothing" failure the backlog complains about for
`enemytarget generic`.

This is accepted rather than routed around. The alternatives are worse: an ownerless arrow would
work but would lose kill attribution and break `BotKilledByPlayerEvent`, and a scoreboard team would
mutate global server state to fix a case the operator caused deliberately. Instead `/tplus bow` and
`/tplus ranged` warn when the gamerule is off in the sender's level, which turns silence into a
message. Deviation 42, and a new 26.2 API note in CLAUDE.md.

---

## Commands

| Command | Effect |
|---|---|
| `create <name> <count> <playerlist> <armor> <tools> <item> <bow>` | A seventh argument on the existing fixed-depth chain |
| `/tplus bow <item>` | Arms every bot, mirroring `/tplus give`. `none` disarms |
| `/tplus ranged <auto\|always\|never>` | Global override. `always` skips the rules but **not** the gates; `never` disables the feature and resets every bot, since one may be mid-draw when it is typed |
| `/tplus towerquota <n>` | The one tuning knob, because squad coordination is the point |

`/tplus info` gains a line, which is the entire reason for choosing named rules over a score:

```
Ranged: RANGED (target_flying, 43t aloft)
Ranged: MELEE  (distance 3.1 < 4)
Ranged: MELEE  (crowded: 6 bots within 4)
```

### Two ways to arm a bot

A bow slot, separate from `defaultItem`, is what lets a bot do both things:

```
/tplus create Hunter 5 none netherite diamond minecraft:netherite_sword minecraft:bow
    → netherite sword in hand, bow stowed, swapped in when a rule fires

/tplus create Archer 3 none none none minecraft:bow
    → the default item is itself a bow, which implies the bow slot
    → melee falls to 0.25, because the 1.8 table has no bow entry
```

The second form is supported because it is what an operator will type, and because refusing it would
be worse than the fist damage. The damage is a consequence of the 1.8 table, not of this design, and
`/tplus create` warns when the default item is a bow.

---

## Testing

### Unit (`src/test`) — where the correctness lives

- `RangedDecision`: every rule in isolation, every gate in isolation, the gate-beats-rule ordering,
  crowding overriding `TOWER_QUOTA`, the hysteresis window holding a RANGED bot through a rule going
  false, and **every gate bypassing that window** — one test per gate, because the
  rules-hysteresise-but-gates-do-not split is the easiest thing here to implement backwards.
- `RangedDecision`: the aloft counter resets when the target UUID changes, so a grounded target
  cannot inherit a Phantom's count.
- `BowBallistics`: a level 20-block shot, a 16-block upward shot, a downward shot, and a target
  moving 0.4 blocks per tick. Each asserts the simulated arrow passes within tolerance of the aim
  point. One fixture is deliberately set beyond `MAX_RANGE`, to pin that the solver has no notion of
  range — that is the gate's job, and a solver that silently refused long shots would make the gate
  untunable. Fixtures use `eyeY - 0.1` as the origin, matching `AbstractArrow`'s constructor — get
  that wrong and the suite happily pins the bias.
- `RangedContext` carries `boolean hasBow` rather than an `ItemStack`, so none of this needs a
  running server.

### GameTest (`src/gametest`) — proving the context is sampled right

- A bot with a bow and a flying target enters `DRAWING` and releases.
- The released arrow damages the target. Extends `an_arrow_hurts_a_bot`, which already pins the
  projectile path.
- A bot inside 4 blocks punches instead of drawing.
- A bot with no line of sight does not draw.
- A crowded bot falls through to navigation.
- `TOWER_QUOTA`: with Q bots in `towerList` near the target, the next bot goes RANGED.
- **Water between bot and target blocks the shot**, where the melee predicate would call the same
  line clear. This is the test that pins the divergence in deviation 38 and stops someone "tidying"
  the ranged check back into `checkFreeSpace`.
- **A drawing bot whose target is removed resets.** Kill or discard the target mid-draw, tick once,
  and assert the bot is `IDLE` with its default item in hand. Without the reset beside
  `mining.stopMining` this hangs forever, and it is invisible from every other test because every
  other test keeps its target alive.
- **A bot on `boatCooldown` keeps the boat.** The one that catches a lava crossing being broken by a
  bow swap.
- **Entering RANGED stops a running mining animation** — `state.miningAnim` no longer contains the
  bot. `BOT_STUCK` fires precisely when a bot is mining and getting nowhere, so this is the common
  path, not an edge case.
- **A RANGED bot's XZ does not change over 100 ticks.** This is the test that pins the whole
  hold-position decision, and the one most likely to catch an accidental reordering of `tickBot`.

That last one deliberately ticks and waits, which CLAUDE.md's GameTest conventions warn against
("`move()` adds `Math.random()` to every jump, so any test that runs 200 ticks and asserts on
position is measuring the walk, not the decision"). The warning does not apply to asserting the
*absence* of movement: if the bot holds position there is no jump and no random term, and if the
branch is wrong the bot moves and the test fails. It is the one case where ticking is the assertion.

Other conventions the suite requires: `@TestHolder` on every test, or it is silently unregistered
and the suite still reports success. Targets are bots, not mock players. `invulnerableTime` and
`noFallTicks` both start at 60 and are drained before any damage assertion. And `Archery`'s per-bot
state is cleared in a `finally` — it is the same shared-JVM trap that `BlockRules`' static
solid-override set already carries.

### A real client session — not optional

The draw pose, the arrow trail and the release sound are invisible to every other tier, and the draw
animation rides a packet path no automated test can observe. CLAUDE.md's own record is the argument:
a manual session found four bugs that 132 GameTests and three RCON runs had passed over, one of them
a missing skin-layer mask and one an entity packet sent before player info. The checklist is the
draw pose appearing and clearing, the arrow rendering in flight, the sound firing once per release,
and the hand restoring to the sword on the flip back to MELEE.

---

## Deviations

To be appended to Plan B's register, which every later plan extends.

38. **A ranged branch in `tickBot`.** Inserted between the melee attack block and the
    grounded-navigation block; returns "handled" while RANGED, which is what suppresses movement.
    Upstream had no ranged combat at all. Three things ride along with it:

    **The no-target reset.** `tickBot` returns above the insertion when the goal finds nothing, so
    the branch also adds `archery.reset(bot)` beside the existing `mining.stopMining(bot)` there. It
    is two touch points in the method, not one.

    **A ranged line-of-sight predicate distinct from the melee one.** `LegacyUtils.checkFreeSpace`
    samples 32 points per block and treats `WATER`, `LAVA`, `FIRE` and vegetation as passable,
    because it is a movement predicate bounded at 4 blocks. The ranged check samples 4 points per
    block, caches on `tickDelay(3)`, and treats water and lava as blocking — an arrow through water
    drops to `WATER_INERTIA = 0.6` and falls short. Same two-ray shape, different constants and a
    different notion of empty.

    **The Ender Dragon aim point.** `Level.getEntities` merges `dragonParts()`, so arrows hit the
    dragon unaided, but `EnderDragon.hurt` quarter-damages every part except the head and a
    projectile cannot be redirected the way deviation 37 redirects a melee hit — so `Archery` aims
    at `dragon.head.position()` instead. An improvement in expectation, not a guarantee.
39. **Arrow damage is vanilla's, not the 1.8 table.** `AbstractArrow.baseDamage = 2.0` scaled by
    velocity. `ItemUtils.getLegacyAttackDamage` is a melee table with no bow entry, whose
    `FIST = 0.25` fallback is the reason today's `/tplus create Archer` bots are useless.
40. **Infinite ammunition.** A fresh arrow stack per shot; `ProjectileWeaponItem.useAmmo` and the
    bot's inventory are never touched. Consistent with infinite cobblestone, water buckets and tool
    durability. Spawned arrows are `Pickup.DISALLOWED`.
41. **`BowItem.releaseUsing` is bypassed.** Only its first step reads the frozen `useItemRemaining`
    counter, and only to recover a number the bot already knows. **This does not deliver the generic
    use-tick**: food, potions and the shield remain exactly as blocked as before, and a working bow
    is not evidence they are close.
42. **Archer bots are gated by the `pvp` gamerule; melee bots are not.** 26.2 moved `pvp` from
    `server.properties` to `GameRules.PVP` (default true), enforced in `AbstractArrow.canHitEntity`
    and `ServerPlayer.hurtServer`. The melee path calls `hurtServer` directly and ignores it.
    Accepted rather than worked around; `/tplus bow` and `/tplus ranged` warn when it is off.
43. **Ranged state lives in `Archery`, not `AgentState`.** The first step of the narrowing the
    backlog asks for, taken on new state where there is no sharing semantic to guess at.

---

## Deliberately not built

**`TARGET_CAMPING`** — target is well above the bot and its Y has stopped rising, meaning it has
settled on a pillar rather than actively climbing. It is the only one of the four candidate rules
needing new per-bot state: `state.btList` and `btCheck` sample the *bot's* own column every 20 ticks
and nothing anywhere records a target's Y history. No field is added for it now — the point is that
adding one later costs a sampler, one `RangedContext` component and one `RangedRule` entry, and
reshapes nothing, because the decision is already a pure function of a record.

**`UNREACHABLE`** — a gap, ravine or lava lake between bot and target. It needs a reachability
concept the agent does not have. The backlog is blunt: *"there is no path to visualise… no
pathfinder, no graph and no cost function."* `BOT_STUCK` catches most of the same situations a few
seconds later and costs one map lookup.

**`TARGET_FLEEING`** — distance trending upward over time. Needs the same kind of history
`TARGET_CAMPING` does and only pays off once the first three rules are tuned.

**A range band, or any retreat.** A bot that backs away to hold a preferred distance is the most
convincing archer and the only option that stops one walking into what it is shooting. It needs a
flee vector in `Navigation`, which the backlog identifies as a genuine design change rather than a
translation — bots have no self-preservation at all today, and `getHealth()` is read in exactly two
places, neither of which branches on it. Hold-position serves all three v1 rules, because a bot
facing a Ghast, a bot standing aside for a towering squad, and a bot that is stuck by definition are
none of them usefully moving.

**A scoreboard team to stop friendly fire.** Considered and rejected: it would work at the engine
level for one line of code, but it mutates global server state, it makes any real player who joins
that team immune to bots, and it would also silently disable bot-on-bot melee. Friendly fire stays
on, with crowding and the firing-line check as the mitigation.

---

## Corrections after implementation

The body above is the design as it was approved. Four things were wrong or missing, and the build
found each of them. They are recorded here rather than edited into the body, so the design and the
corrections stay distinguishable — the same reason deviation 36 carries its own correction.

**Arrival height is not monotonic in pitch, so a plain bisection cannot solve the trajectory.**
The Aiming section says to "binary-search the launch pitch". That does not converge: the curve is
negative infinity at *both* ±89°, where horizontal speed is about 0.05 blocks a tick and the arrow
never covers the distance at all, and it rises to a peak between them. A bisection reads "never
arrived" as "arrived low" and declares every shot out of reach — nine of ten ballistics tests
failed on the first run. `BowBallistics` scans coarsely downward for the first crossing and
bisects inside that bracket, which also selects the flatter of the two arcs.

**The firing-line check must exclude the target, not just the shooter.** Bots are `ServerPlayer`s,
so a bot hunting another bot — which is exactly what `TOWER_QUOTA` and the `NEAREST_BOT` goals
produce — put its own target on the line and vetoed every shot.
`a_bot_draws_and_fires_at_a_flying_target` caught it immediately: the decision read
`RANGED / TARGET_FLYING` and no arrow ever left.

**`broadcastEntityData` must not build a packet when nothing is dirty.**
`SynchedEntityData.packDirty()` returns null, and `ClientboundSetEntityDataPacket.pack()` iterates
it unchecked, so the NPE lands in the encoder on a Netty thread — invisible to the server tick and
to every GameTest, because `BotConnection` swallows packets without encoding them. It dropped the
first real client within seconds of a bot drawing. Vanilla's `ServerEntity.sendDirtyEntityData`
guards on the same null; this now does too, and the two pre-existing shield call sites route
through the same helper. See CLAUDE.md's testing-tier note.

**`/tplus info` prints the rule's label, not a rendered explanation.** The Commands section shows
`Ranged: MELEE (distance 3.1 < 4)`. What ships is `Ranged: MELEE (too_close)` — the
`RangedRule` label. The label is stable and the numbers are not, which is the more useful thing to
pin, but the examples above overstate it.
