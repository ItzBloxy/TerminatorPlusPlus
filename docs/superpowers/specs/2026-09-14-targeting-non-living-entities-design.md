# Targeting non-living entities — design

Bots cannot target an end crystal. They cannot target a boat, a minecart, an item frame or a
painting either, and for one reason: the whole targeting pipeline is typed on `LivingEntity`, and
none of those are one.

Upstream has the same limit — Bukkit's `locateTarget` returned a `LivingEntity` scanned out of
`world.getLivingEntities()` — so this is a deliberate divergence rather than a translation.
Register it as deviation 36, and the Ender Dragon fix that fell out of the audit as 37.

Everything below was checked against
`build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar`, never against a Paper jar.

---

## The problem

`Targeting.locateTarget` returns `@Nullable LivingEntity`, and its scan is:

```java
level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> true)
```

The entities this is about reach `Entity` by four different routes — `EndCrystal` and
`BlockAttachedEntity` extend it directly, boats and minecarts arrive through `VehicleEntity`, item
frames through `HangingEntity` above `BlockAttachedEntity`, and shulker bullets through
`Projectile`. What they share is only the negative: none is a `LivingEntity`. So none is in that
scan, none can be returned through that signature, and no goal can ever find one.

The gap is visible from the command layer too. `BotCommands.enemyTargetSpecific` filters the
selector's results with `e instanceof LivingEntity` and reports what it dropped, so
`/tplus enemytarget specific @e[type=end_crystal]` already answers "none of the selected entities
can be targeted." It is an honest message about a limit nobody chose.

## The audit

The mechanical question is: which entities can a player's melee hit actually do something to?
Vanilla answers it in two places — `Entity.isAttackable()` and `Entity.isPickable()` decide whether
you can aim at a thing, and `hurtServer` decides what happens when you connect. Reading all three
across every class under `net.minecraft.world.entity` gives the complete picture.

### Targetable today

Everything that is a `LivingEntity`: all mobs, players, other bots, and armour stands that are not
markers (`ArmorStand.isPickable()` is `super.isPickable() && !isMarker()`).

### Not targetable, and should be

Not a `LivingEntity`, but a hit genuinely does something:

| Entity | What a hit does |
|---|---|
| **End Crystal** | `remove(KILLED)`, then explodes at 6.0F with `ExplosionInteraction.BLOCK` |
| **Boats** — every wood, chest boats, rafts | `setDamage(getDamage() + damage * 10.0F)`, breaks past its threshold |
| **Minecarts** — all seven | the same `VehicleEntity.hurtServer` |
| **Item Frame**, **Glow Item Frame** | `kill()` and `dropItem()` |
| **Painting** | the same `BlockAttachedEntity.hurtServer` |
| **Lead Knot** | likewise |
| **Shulker Bullet** | `destroy()`, with a hurt sound and crit particles |
| **Ghast fireball**, **Wind Charge**, **Breeze Wind Charge** — `#minecraft:redirectable_projectile` in full | deflected |

`BlockAttachedEntity.hurtServer` refuses when `MOB_GRIEFING` is off *and*
`source.getEntity() instanceof Mob`. A bot is a `ServerPlayer`, not a `Mob`, so it passes that gate
in either case — the same way a real player does.

### Not targetable, and correctly so

Either `isAttackable()` is `false`, or `isPickable()` keeps its `false` default from `Entity`:

`ItemEntity`, `ExperienceOrb`, `FallingBlockEntity`, `EyeOfEnder` and `FireworkRocketEntity` all
declare `isAttackable()` as `false`. `AreaEffectCloud`, `Display`, `Marker`, `LightningBolt`,
`OminousItemSpawner` and `EvokerFangs` never override `isPickable()`, so they inherit `false`.
Nothing needs to be done about any of them.

`FallingBlockEntity` is worth singling out because it overrides `isPickable()` to
`!isRemoved()` — true, in other words — and is caught only by `isAttackable()`. Both halves of the
gate do real work; neither is redundant.

### Two that pass the gate and never die

`PrimedTnt` (`isPickable()` is `!isRemoved()`) and `Interaction` (`isPickable()` is `true`) are
both pickable and inherit `isAttackable() == true`, but each declares `hurtServer` as `final`
returning a constant `false`. They pass the gate and cannot be damaged by anything, ever.

**This is accepted rather than fixed, deliberately.** The alternatives were a maintained exclusion
set — two entries today, stale on the next Minecraft release, and blind to modded entities — or
reading `hurtServer`'s discarded return value, which also returns `false` transiently during a
`LivingEntity`'s invulnerability window and so would need a consecutive-failure counter and
per-bot state. Neither is worth it for two entities an operator has to name explicitly.

The consequence, which must be written down in the code, the spec and the README rather than
discovered: `/tplus enemytarget generic minecraft:tnt` sends bots to stand beside lit TNT swinging
at something that cannot be hurt, and `minecraft:interaction` does the same against a map-maker's
hitbox. The command succeeds; the swing can never land.

### The Ender Dragon

The dragon is already targetable — it is a `Mob` — but the hit lands in the wrong place.

`EnderDragon.hurtServer` routes to `this.hurt(level, this.body, source, damage)`, and that method
opens:

```java
if (part != this.head) {
    damage = damage / 4.0F + Math.min(damage, 1.0F);
}
```

So a bot swinging a netherite sword for 8 damage delivers 3. A player aiming at the head delivers
8. The dragon's own `isPickable()` is `false`, which is how vanilla forces players to aim at a part
in the first place. `EnderDragonPart` is a `PartEntity`, and `ServerLevel` keeps those in a
separate `dragonParts` map keyed by network id — its entity callback explicitly skips
`instanceof PartEntity` when indexing — so widening the scan cannot reach them. A redirect at the
attack is the only route; there is a public `ServerLevel.dragonParts()` accessor, but scanning it
would make parts compete with their own parent as candidates for no gain.

Upstream on 1.21.1 had the identical behaviour. This is fixed rather than kept, because the cost is
two lines and the symptom — quarter damage — is not something an operator could ever discover from
the outside.

## Scope

In:

- The targeting pipeline's type, from `LivingEntity` to `Entity`
- The `ENTITY` goal's scan, and the gate on it
- The `enemyTargetSpecific` command filter, which currently spells the same rule differently
- The dragon head redirect

Out:

- **Self-preservation.** A bot that kills an end crystal is inside a power-6 explosion and dies.
  Nothing in this codebase branches on health — `getHealth()` is read in exactly two places, and
  neither decides anything — so making crystals the one exception would be an inconsistent special
  case. It stays the backlog item it already is, and the README says plainly that a bot pointed at
  crystals trades itself for each one.
- **Ranged attacks and a standoff.** Same reason, plus they need the use-tick the backlog already
  describes.
- **The other nine goals.** `Monster`, `Mob`, `Raider`, `ServerPlayer` and `Bot` are all
  `LivingEntity`. Widening their scans would change nothing except cost.

## The change

### The type widens to `Entity`

`Targeting.locateTarget` returns `@Nullable Entity`, and that flows outward through
`validateCloserEntity`, `TerminatorLocateTargetEvent`, `Bot.attack`, `LegacyAgent.attack` and
`tickBot`'s local, and the target parameter of `BlockScan.clutch`, `BotBehaviors.resetHand`,
`miscellaneousChecks`, `waterMlg` and `boatOverLava`, `Navigation.move`, `swim`, `checkUp`,
`tower` and `checkSide`, and `SurroundingScan.checkNearby`.

**No method body changes in any of them.** Every member read on the target anywhere in the agent
is declared on `Entity`:

| Member | Where it is declared |
|---|---|
| `position()`, `getEyePosition()`, `isAlive()`, `level()` | `Entity` |
| `invulnerableTime` | `Entity`, as a public field — not `LivingEntity`, which merely maintains it |
| `hurtServer(ServerLevel, DamageSource, float)` | `Entity`, abstract |
| `getType()`, `getUUID()` | `Entity` |

The one type test on the target, `target instanceof ServerPlayer` in `LegacyAgent.attack`'s
invincibility gate, is unaffected.

`EnemyTarget` needs no change at all. `matches(EntityType<?>, UUID)` already takes the two fields
rather than an entity, for the testability reason its javadoc gives, and both are on `Entity`.

`TerminatorLocateTargetEvent` is the only extension point this port keeps, and both its consumers
are in `AgentTests`: `VetoHandler`, which counts and cancels, and `RetargetHandler`, which holds a
`LivingEntity` and hands it to `setTarget`. **Both survive the widening unmodified** — a
`LivingEntity` argument still satisfies an `Entity` parameter. Nothing in the repository assigns
`getTarget()` to a `LivingEntity`, which is the direction that would not compile. So the type
change is source-compatible everywhere it is actually used today, and the breakage it could cause
is hypothetical rather than pending.

### Only the `ENTITY` scan widens

A new helper mirroring the existing one, so the two sit side by side and the difference is legible:

```java
private static List<? extends Entity> allEntities(ServerLevel level) {
    return level.getEntities(EntityTypeTest.forClass(Entity.class), e -> true);
}
```

`Level.getEntities()` — the `LevelEntityGetter` accessor with `getAll()` on it — is `protected
abstract`, so the type-test form is the reachable one. It is also the shape `livingEntities`
already uses, which matters more than brevity here.

Cost is unchanged in kind: `getEntities(EntityTypeTest, predicate)` walks every entity and tests
each, so the living scan was already O(all entities). Upstream's own header comment on the file
this was ported from reads "Yes, this code is very unoptimized, I know."

One new interaction worth naming rather than discovering: `AgentState.boats` tracks the boats
`BotBehaviors.boatOverLava` spawns under bots crossing lava, and those become targetable like any
other boat. An operator who sets `generic minecraft:oak_boat` can therefore have a bot destroy the
boat it is standing in and drop into the lava it was crossing. It takes naming boats explicitly, so
it is documented rather than guarded — the same call as the crystal.

### The gate

```java
public static boolean isTargetable(Entity entity) {
    return entity.isAttackable() && entity.isPickable();
}
```

Vanilla's own pair, and nothing else. It is re-evaluated every tick rather than cached, which
matters because several of these are dynamic: `AbstractBoat.isPickable()` is `!isRemoved()`,
`AbstractArrow.isPickable()` is `super.isPickable() && !isInGround()`, and
`AbstractArrow.isAttackable()` is `is(EntityTypeTags.REDIRECTABLE_PROJECTILE)`.

The method carries a comment naming `PrimedTnt` and `Interaction`, saying that they pass it and
can never be damaged, and saying that this was decided rather than missed — otherwise the next
reader "fixes" it.

### The dragon redirect

In `LegacyAgent.attack`, when the target is an `EnderDragon`, `bot.attack` receives `dragon.head`
— a `public final` field, so no access transformer. All three gates stay against the dragon
itself: that is what the scan found, what `validateCloserEntity` compared, and what navigation
aims at. Only the recipient of `hurtServer` moves.

`EnderDragonPart.hurtServer` forwards to `parentMob.hurt(level, this, source, damage)`, where
`part != this.head` is now false and the quarter-damage reduction does not apply.

### The command filter

`BotCommands.enemyTargetSpecific` replaces `e instanceof LivingEntity` with
`Targeting.isTargetable(e)`. One rule, one home, two callers — and the "ignored N selected
entities that cannot be targeted" message becomes true again.

**The comment above that filter has to go with it.** It currently reads "`locateTarget` returns a
`LivingEntity` and `@e` matches boats and item frames", which stops being an explanation and starts
being a lie the moment boats and item frames are targetable. In a codebase whose comments exist to
stop the next reader "fixing" something, leaving that one standing would be worse than the bug it
described.

`/tplus enemytarget generic <type>` gets no equivalent check, because there is nothing to check:
`isAttackable` and `isPickable` are instance state, and a generic target names a type that may have
no instances yet. So `generic minecraft:item` is accepted and matches nothing, where the empty-tag
guard three lines above exists to prevent exactly that. The asymmetry is commented at the guard
and recorded in the backlog.

## Why it cannot regress

The nine living goals keep `livingEntities()` and their existing predicates, so their candidate
sets are identical to today's, entity for entity.

For the `ENTITY` goal, almost every entity that was targetable before still is: `LivingEntity`
declares `isPickable()` as `!isRemoved()`, and no `LivingEntity` overrides `isAttackable()` to
`false`. There are exactly **two narrowings**, both of them fixes, and both registered under
deviation 36 rather than left to look like drift:

- **Armour-stand markers**, which `ArmorStand.isPickable()` already excludes. A marker has no
  hitbox and takes no damage, so a bot that used to walk toward one forever now ignores it.
- **Spectators**, because `Player.isPickable()` is `!isSpectator() && super.isPickable()`. A
  spectator cannot be hit by anything, so `enemytarget specific` naming one was another
  successful command that did nothing.

Neither narrowing touches the nine living goals, which do not consult the gate. In particular
`NEAREST_PLAYER` still finds players in any gamemode, spectators included — that goal's whole
point is that it ignores gamemode, and `NEAREST_VULNERABLE_PLAYER` is the one that does not.

## Testing

Unit tests gain nothing. `EnemyTarget` is untouched, and `isTargetable` calls instance methods on
`Entity`, which cannot be exercised without a world — designing around that would mean inventing a
type-level shadow of vanilla's rule, which is worse than testing it where it lives.

GameTests in `EnemyTargetTests`:

1. **An end crystal is found.** Spawn one, set the bot's target to
   `EnemyTarget.ofTypes(Set.of(EntityTypes.END_CRYSTAL), …)`, call `locateTarget` directly and
   assert the crystal comes back.
2. **A boat is found**, the same way — proving the widening is general rather than crystal-shaped.
3. **An item entity is not found** even when its own type is named, proving the gate bites.
4. **An armour-stand marker is not found**, pinning the narrowing above.

**The crystal test must not let the bot actually swing.** A power-6 explosion with
`ExplosionInteraction.BLOCK` breaks blocks, GameTests share a level, and the blast radius reaches
neighbouring structures. Calling `locateTarget` directly is also what CLAUDE.md's "prefer a direct
call to ticking and waiting" rule already requires, so the safe form and the correct form are the
same form.

Every assertion is phrased as a rule — "returns the crystal, not the bot" — never as "the level is
otherwise empty". The `ENTITY` scan has no range limit and other tests' entities share the level;
`EnemyTargetTests` has been caught by this once already.

Manual, on a real client, because no tier below it can cover them:

- A bot destroys an end crystal, and dies to it.
- A bot kills the Ender Dragon in roughly a third of the swings it used to take — for a hit worth
  8, `8 / 4 + min(8, 1)` is 3, so the redirect is worth 2.7× per swing.

## Docs

**`README.md`** — the `enemytarget` section gains a paragraph on what can be targeted: anything a
player could hit, which now includes end crystals, boats, minecarts, item frames and paintings,
and excludes dropped items, experience orbs and armour-stand markers. With both warnings stated:
a bot pointed at end crystals trades itself for each one, and TNT and interaction entities can be
named but never hurt. "What is new here" gains the widening.

**Plan B's deviation register** — entries 36 and 37. Not a new list.

**`docs/backlog.md`** — the `generic <type>` validation gap.

## Deviations to register

36. **Targeting widened from `LivingEntity` to `Entity`.** Upstream's `locateTarget` returned a
    Bukkit `LivingEntity` scanned from `world.getLivingEntities()`, so end crystals, vehicles,
    decorations and shulker bullets were invisible to every goal and `/tplus enemytarget specific`
    reported them as untargetable. The pipeline is now `Entity` throughout, the `ENTITY` goal
    scans all entities gated on vanilla's own `isAttackable() && isPickable()`, and the other nine
    goals keep the living scan unchanged. Three consequences to record: the `ENTITY` goal now
    correctly ignores armour-stand markers and spectators where it used to chase both;
    `TerminatorLocateTargetEvent.getTarget()` changed type, which both in-repo listeners survive
    unmodified and which would only break an addon that assigned the result to a `LivingEntity`;
    and `PrimedTnt` and `Interaction` pass the gate and can never be damaged, which was accepted
    rather than missed.
37. **Bots hit the Ender Dragon's head.** `EnderDragon.hurtServer` routes to the body, and
    `EnderDragon.hurt` reduces any non-head hit to `damage / 4 + min(damage, 1)`, so bots did
    roughly a third of the damage a player does for no reason visible from outside. `attack` now
    passes `dragon.head` to `bot.attack`; the distance, invincibility and `invulnerableTime` gates
    still measure against the dragon itself. Upstream had the same behaviour.

## Backlog

`/tplus enemytarget generic <type>` cannot validate that the named type is targetable, because
`isAttackable()` and `isPickable()` are instance state and a generic target may have no instances
yet. `generic minecraft:item` is accepted and matches nothing. A type-level table would fix it and
would have to be maintained against every Minecraft release; the live re-check is the reason it has
not been built.

## The release

Separate commit from the fix, so that the targeting work is not held up if the release mechanics
need another pass.

**Version** — `5.1.0-ALPHA`, tagged `v5.1.0-ALPHA`. Five user-visible changes since the bare
`ALPHA` tag: shears on leaves, `/tplus descendrange`, the walk gear, the mining-sound fix, and this.
A minor bump, not a patch. The versioned tag also starts a convention that can hold more than one
release; the old `ALPHA` tag stays where it is.

**`CHANGELOG.md`**, new at the root, with a `## v5.1.0-ALPHA` section covering those five. It
exists so the workflow has hand-written notes to read — `--generate-notes` would publish 22 commit
subjects, fourteen of which start with `docs:`.

**`.github/workflows/release.yml`**, on `v*` tag pushes:

- The same toolchain as `compile.yml` — checkout v7, temurin JDK 25, setup-gradle v6 with
  `cache-provider: basic`. That last one carries over a licensing decision, not a performance one:
  v6 defaults to a closed-source cache component under separate terms. A new workflow that quietly
  reintroduced it would undo the choice made in `4771e8e`.
- **A guard that the tag matches `mod_version`.** Push `v5.1.0-ALPHA` while `gradle.properties`
  still reads `5.0.0-ALPHA` and you get `tplus-5.0.0-ALPHA.jar` attached to a release named
  v5.1.0-ALPHA. Three lines make it impossible.
- `./gradlew build`, then `./gradlew runGameTestServer`, in that order, so an untested jar cannot
  ship — the reasoning `compile.yml` already gives for its artifact upload.
- Publishing with `gh release create`, preinstalled on GitHub runners. First-party, so no
  third-party action enters the supply chain of a repository that has already declined a
  closed-source CI component. `--prerelease` when the version carries a `-` suffix; `--notes-file`
  from the `CHANGELOG.md` section matching the tag, and the job fails if that section is missing.
- `permissions: contents: write`, and nothing else.

**Sequencing.** The workflow has to be on the branch *before* the tag is pushed — GitHub runs the
workflow as it exists at the tagged commit, so a tag pushed first does nothing at all.

The tag push is not done without being asked for. It publishes a public release with a downloadable
jar, which is not something to infer from a design conversation.
