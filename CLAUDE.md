# Terminator++

A port of the TerminatorPlus Bukkit/Paper plugin to a **server-side NeoForge mod** for Minecraft
26.2. Bots are `ServerPlayer`s driven by a fake connection; they hunt a target, mine through walls,
tower, clutch out of falls and fight back.

Upstream paused on Paper 1.21.1. This is not positioned as a successor — it is another option for
people who want a version that runs on a current Minecraft. `README.md` is the user-facing side of
that; this file is the working guide.

**The name is only on the outside.** `mod_id` is still `tplus`, the package is still
`net.nuggetmc.tplus`, the command is still `/tplus` and the jar is still `tplus-<version>.jar`.
Renaming any of those means touching the access transformer, the gametest namespace, every import
and every operator's muscle memory, for no functional gain — so it has not been done, and
`mod_name` in `gradle.properties` carries the new name alone.

**Two spellings, one name.** It is **Terminator++** wherever a `+` is fine: `mod_name`, the
README, this file. Where a `+` does not belong it is **TerminatorPlusPlus**, matching the
repository name — `rootProject.name` and the CI artifact. It is never "TerminatorPlus++",
which reads as three pluses. A plain `TerminatorPlus` left in the tree is either upstream's
plugin or this mod's main class, and neither of those changes.

**Fidelity is the point.** Method bodies are translated, not redesigned. Where upstream is odd, the
port is odd in the same way and says so in a comment. Every deliberate divergence is in the
deviation register (see *Docs* below) — if you change behaviour, add an entry.

That constraint is about *translation*, not about the project. New behaviour upstream never had is
welcome — Plan D added three such things — but it is designed deliberately, registered, and kept
distinguishable from a translation that drifted.

## Branches

| Branch | What it is |
|---|---|
| `master` | The NeoForge port. All work lands here |
| `paper-original` | The original Paper 1.21.1 plugin, tracking `origin/master` upstream. **Never modify.** This is the faithfulness oracle |

The two branches share history — `master` descends from `paper-original` — so the original of any
file is one command away. Read it with `git show paper-original:<path>`, and do that constantly
rather than occasionally.

**The oracle was called `master` until Plan D**, when the port became the project's main line.
Anything quoting `git show master:` predates that rename and is reading the wrong branch.

```bash
git show paper-original:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java
```

## Commands

```bash
./gradlew build              # compile + 98 unit tests
./gradlew runGameTestServer  # 143 GameTests, headless, ~10s
./gradlew runServer          # dev server, RCON on 25575 (password in run/server.properties)
./gradlew runClient          # dev client — connect to localhost
./gradlew jar                # the production jar, build/libs/tplus-*.jar
```

`tools/` has an RCON client and the recipe for a **production** NeoForge server test. Read
`tools/README.md` before reaching for either — the dev run is not representative of production.

## Verify against the patched jar, never a Paper jar

Every vanilla signature goes to
`build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar`. Paper patches vanilla classes, so
checking there gives wrong answers (`detectEquipmentUpdates` is public in Paper, private in vanilla).

```bash
J=build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar
unzip -p $J net/minecraft/server/level/ServerPlayer.java | grep -n "updateOptions" -A 10
```

Mojang de-obfuscated the server in 26.1, so these are real names with no reobfuscation step.

## The test tiers, and what each one actually catches

This is the most useful thing in this file. Each tier catches a class of defect the others cannot:

| Tier | Catches | Misses |
|---|---|---|
| Signature checks against the sources jar | Compile errors, renames | Everything else |
| Unit tests (`src/test`) | Pure maths — vectors, offsets, the scheduler | Anything needing a world |
| | `Items.X` and `EntityTypes.X` resolve here; `new ItemStack(item)` does not ("Components not bound yet"). Design value types to sit on this side of that line — `EnemyTarget.matches` takes a type and a UUID rather than an `Entity` for exactly that reason | |
| GameTests (`src/gametest`) | Integration: mining, clutching, block rules | Anything needing a real client or a real server runtime |
| `runServer` + RCON | Server-runtime crashes, command trees | Anything visual |
| **A real client** | Rendering, skins, projectile collision, packet ordering | — |
| Diffing against `paper-original` | Silent behaviour drift | — |

Empirically: `runServer` found a fatal `ConfigSync` crash that 51 GameTests passed over, and a
manual client session found **four** bugs invisible to 132 GameTests *and* to RCON — bots spawning
in CREATIVE (immune to arrows), entity packets sent before player info, unsigned skins that render
nothing, and a missing skin-layer mask. Do not treat a green suite as "it works".

Unit tests also cost seconds where GameTests cost ten, so it is worth **designing value types to be
testable without a world**: `EnemyTarget.matches` takes an `EntityType` and a `UUID` rather than an
`Entity` for exactly that reason, and the whole targeting rule is unit tested as a result.

## GameTest conventions

- **`@TestHolder` is required.** Without it a test is silently unregistered and the suite still
  reports success. Five tests once sat dead this way.
- **Tests share a level, not just a JVM.** Anything that scans the whole level sees entities
  belonging to tests running at other structure positions, and the agent's target scan has no
  range limit. Assert the rule — "not itself", "not the cow" — never that the level is empty.
- **Prefer a direct call to ticking and waiting.** `move()` adds `Math.random()` to every jump, so
  any test that runs 200 ticks and asserts on position is measuring the walk, not the decision.
  Three tests passed, failed, then passed again on an unchanged build before this rule existed.
- **Clear static state in a `finally`.** GameTests share a JVM. `BlockRules`' solid-override set is
  static; a test that leaves an entry behind changes how every later test's bots walk.
- **Targets should be bots, not mock players.** `makeMockServerPlayer` has a null connection and
  crashes the server tick when added to a level. `makeMockServerPlayerInLevel` works but hardcodes
  CREATIVE, so it only suits goals that ignore gamemode.
- A fresh `ServerPlayer` carries `invulnerableTime = 60` and `noFallTicks = 60`. Drain them before
  asserting on damage or falling, or the test proves nothing.

## 26.2 API notes

Renames and traps this port walked into:

- `EntityType` constants live in `EntityTypes`; `ResourceLocation` is `Identifier`;
  `ResourceKey.location()` is `identifier()` — but `TagKey` kept `.location()`. Both spellings sit
  side by side in `ResourceOrTagArgument`'s two `asPrintable()` implementations.
- `ServerLevel.getEntity(UUID)` is `getEntityInAnyDimension(UUID)`. `getEntity(int)` is still the
  network-id lookup.
- Mobs gained per-mob subpackages: `world.entity.animal.cow.Cow`,
  `world.entity.monster.zombie.Zombie`.
- Gamerules are snake_case: `spawn_mobs`, `spawn_monsters`, `advance_time` — not `doMobSpawning`
  or `doDaylightCycle`. `/help gamerule` over RCON lists them.
- `ResourceOrTagArgument` accepts a type or a `#tag`, and `Result.unwrap()` gives an
  `Either<Holder.Reference, HolderSet.Named>` — so a tag can be expanded to concrete values rather
  than kept as an opaque predicate.
- `Direction.step()` is `getUnitVec3()`. `Blocks.CHAIN` is `Blocks.IRON_CHAIN`.
- `Blocks.LIGHTNING_ROD` is a `WeatheringCopperCollection` — use `instanceof LightningRodBlock`.
- Dyed families are `ColorCollection<Block>` with `.pick(DyeColor)`.
- `ItemInput.createItemStack(int)` takes one argument.
- **Constructing an `ItemStack` in a static initialiser throws "Components not bound yet"** and
  breaks mod loading outright. Hold `Item` constants and build stacks at the call site.
- Access transformers in `src/main/resources/META-INF/accesstransformer.cfg` — currently
  `LivingEntity.detectEquipmentUpdates()V` and `PlayerList.players`.

## Docs

- `docs/superpowers/specs/2026-09-12-terminatorplus-neoforge-port-design.md` — the design spec
- `docs/superpowers/plans/…-a-foundation.md` — Plan A, complete
- `docs/superpowers/plans/…-b-agent.md` — Plan B, complete. **Its deviation register is the one
  every later plan extends.** Do not start a new list
- `docs/superpowers/plans/…-c-environment.md` — Plan C, complete
- `docs/superpowers/specs/…-loadouts-and-entity-targeting-design.md` — Plan D's spec. These are
  features upstream never had, so it stands in where there is no `paper-original` to check against
- `docs/superpowers/plans/…-d-loadouts-targeting.md` — Plan D, complete
- `docs/backlog.md` — what is not built yet, and why
- `README.md` — the user-facing description, install and full command reference. Keep it true; it
  is the only document a stranger reads

## House style

Comments explain *why*, especially when the code looks wrong. Most oddities here are upstream's and
deliberate; a reader who does not know that will "fix" them. When translating, say what upstream
did and what changed. Commit messages carry the same weight — they are the record of why a
divergence exists.
