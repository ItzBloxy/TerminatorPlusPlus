# TerminatorPlus — NeoForge 26.2 Port

**Date:** 2026-09-12
**Status:** Approved design
**Source:** [HorseNuggets/TerminatorPlus](https://github.com/HorseNuggets/TerminatorPlus) @ `ff92206` (4.5.1-BETA, Paper 1.21.1)
**Target:** Server-side NeoForge mod, Minecraft 26.2

## 1. Summary

TerminatorPlus creates server-side player bots with an emphasis on human-like behavior. It is a
Paper plugin stranded on Minecraft 1.21.1, and upstream has paused development indefinitely.

This project ports it to a **server-side-only NeoForge mod** targeting Minecraft 26.2. Bukkit is
dropped entirely. Vanilla clients connect with no client-side installation.

### Decisions

| Decision | Choice |
|---|---|
| Platform | NeoForge server-side mod (Bukkit dropped) |
| Fidelity | Faithful translation of existing behavior |
| v1 scope | Spawn, move, fight, despawn |
| Verification | NeoForge GameTests + pure JUnit unit tests |
| MC target | 26.2, structured so version bumps are cheap |
| `isFakePlayer()` | Returns `false` — bots behave as real players |

### Why NeoForge is a real choice and not just a bigger one

Historically NeoForge's advantages over Bukkit were official mappings and unrestricted access to
game internals. **Both are now true on Paper too**: Mojang stopped obfuscating the server jar as of
26.1, and Paper 26.1+ rejects reobfuscated plugins outright.

What NeoForge still offers this project specifically:

- **A real test harness.** NeoForge's `testframework` provides annotation-driven GameTests with
  synthesised templates, giving automated in-world verification; `EphemeralTestServerProvider`
  additionally injects a headless `MinecraftServer` into JUnit for registry- and datapack-backed
  tests (but **not** for anything needing a world — see the correction in section 6). Bukkit has no
  equivalent to either. For a faithful translation of 1,600 lines of movement AI, this is the
  difference between provable and hopeful.
- **Mixins and Access Transformers** for anything the vanilla API does not expose.
- **No plugin-API churn.** Paper 26.2 alone removed Adventure 4 APIs and changed the entity class
  hierarchy; the mod path only tracks vanilla.

The cost is honest and large: **~91% of the existing codebase is Bukkit-coupled** (only ~700 of
8,252 LOC are platform-agnostic). This is a rewrite of the platform layer, not a recompile.

## 2. Verified API research

Every claim below was verified by inspecting real artifacts, not from documentation or memory: the
Minecraft 26.2 vanilla server jar, Paper 26.2 build 123 (paperclip-patched to a readable jar),
`paper-api` 26.2.build.123-stable, authlib 9.0.75, netty-transport 4.2.15.Final, and NeoForge
26.2.0.87 (universal and sources).

### 2.1 The single most important fact

**Mojang stopped obfuscating the server jar in 26.1.** The 26.2 version manifest no longer publishes
`server_mappings`, and `net/minecraft/server/level/ServerPlayer.class` exists in the jar under its
real name.

The plugin's most fragile machinery exists solely to fight obfuscation, and it all disappears:

- `MockConnection` reflects `Connection.class.getDeclaredField("q")`. The field is now literally
  named `packetListener`.
- `NMSUtils` (45 lines) scans `SynchedEntityData` for "a private final `Int2ObjectMap`" to hand-roll
  a data-value list. That field is now a `DataItem[]` array, so **the hack is broken** — but 26.2
  exposes a public `getNonDefaultValues()` returning exactly the `List<DataValue<?>>` it was
  building. The class is deleted.
- The `Terminator` interface's `getBotX()` / `isBotX()` naming scheme — whose source comment reads
  `//Has to be named like this because paper re-obfuscates it` — has no remaining reason to exist.

### 2.2 Breaking changes, 1.21.1 to 26.2

| # | Item | 1.21.1 | 26.2 | Impact |
|---|---|---|---|---|
| 1 | Java | 21 | **25** | Toolchain |
| 2 | `GameProfile` | extendable class | **`final record`** (authlib 9.0.75) | `CustomGameProfile extends GameProfile` is impossible; `getProperties()` becomes `properties()` |
| 3 | Damage hook | `hurt(DamageSource, float)` | **`hurtServer(ServerLevel, DamageSource, float)`** | `Bot`'s override |
| 4 | `SynchedEntityData` | `Int2ObjectMap` field | `DataItem[]` plus public `getNonDefaultValues()` | `NMSUtils` deleted |
| 5 | `Connection.send` | `PacketSendListener` | `ChannelFutureListener` | `BotConnection` overrides |
| 6 | `LevelChunk.loaded` | public field | private plus `setLoaded(boolean)` | `loadChunks()` |
| 7 | Equipment sync | `detectEquipmentUpdatesPublic()` (Paper) | vanilla `detectEquipmentUpdates()` is public | `doTick()` |
| 8 | `Material.CHAIN` | exists | `IRON_CHAIN` | `LegacyMats` — the only casualty of 145 Materials used |
| 9 | `ClientboundAddEntityPacket` | last arg `float` | `double` | Trivial |
| 10 | `ResourceLocation` | — | renamed `Identifier` | Not used here, but makes older docs misleading |

### 2.3 Verified unchanged

The load-bearing mechanism survives intact:

- `ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ClientInformation)`
- `ServerGamePacketListenerImpl(MinecraftServer, Connection, ServerPlayer, CommonListenerCookie)`
- `CommonListenerCookie.createInitial(GameProfile, boolean)`
- `ClientInformation.createDefault()`
- `ServerLevel.addNewPlayer` and `ServerLevel.addFreshEntity`
- All eight clientbound packets used: `AddEntity`, `SetEntityData`, `PlayerInfoUpdate` (including
  `createPlayerInitializing`), `PlayerInfoRemove`, `RotateHead`, `SetEquipment`, `RemoveEntities`,
  `BlockDestruction`
- Entity and LivingEntity internals: `entityData`, `dead`, `inventoryMenu`, `containerMenu`,
  `displayName`, `move`, `push`, `swing`, `startUsingItem`, `stopUsingItem`, `damageSources()`
- Netty 4.1 to 4.2 changed nothing about `AbstractChannel`'s abstract methods

### 2.4 NeoForge 26.2 facts

- **Toolchain:** ModDevGradle `2.0.147`, NeoForge `26.2.0.87`, Gradle `9.2.1`, Java 25.
- **Vanilla clients connect.** `NetworkRegistry.initializeOtherConnection` disconnects a vanilla
  client only when the server has **non-optional registered payloads**. This mod registers no
  payloads, blocks, items, or entity types — bots are vanilla `EntityType.PLAYER`. Server-side-only
  is achievable with no client mod.
- **`FakePlayer` proves the approach but must not be extended.** NeoForge's own
  `FakePlayer extends ServerPlayer` uses the identical constructor-plus-fake-connection pattern.
  However it calls `setInvulnerable(true)` and no-ops `tick()`, `die()`, and `canHarmPlayer()` —
  each of which a PvP bot requires. `Bot` extends `ServerPlayer` directly.
- **Worth taking from it:** `FakePlayerAdvancements`, which fixes a real memory leak
  ([NeoForge #1487](https://github.com/neoforged/NeoForge/issues/1487)) affecting any fake player
  whose UUID is not a real account. TerminatorPlus generates random UUIDs and is exposed today.
- **`FakeConnection` has a two-statement body** — `super(PacketFlow.SERVERBOUND)` in the constructor
  plus a no-op `setListenerForServerboundHandshake`. Together with unobfuscated field names this
  collapses `MockConnection` (68 lines) and `MockChannel` (81 lines) to roughly 15.
- **MDK defaults lag the latest releases.** The 26.2 MDK ships `neo_version=26.2.0.82` and
  ModDevGradle `2.0.146`; the newest at time of writing are `26.2.0.87` and `2.0.147`. This design
  targets the newer pair.
- **`BlockTags` covers the material table.** `FENCES`, `FENCE_GATES`, `WALLS`, `DOORS`, `TRAPDOORS`,
  `SLABS`, `STAIRS`, `LEAVES`, and `CLIMBABLE` all ship in vanilla.

### 2.5 The Vec3 immutability hazard

Bukkit's `Vector` has `protected double x/y/z` with in-place setters. Vanilla's `Vec3` has
`public final double x/y/z`.

The existing code depends on that mutability throughout: `velocity.setY(0)`, `velocity.add(vel)`,
`sum.normalize().multiply(max)`. Translated naively, `vec3.add(v)` returns a **new** `Vec3` and
discards it, and **javac issues no warning**. Each such site is a chance to silently alter bot
physics in a way no compiler catches and only play-testing reveals.

**Count: 102 sites** across the 40 v1-relevant files. Methodology — the unambiguous mutators, which
have no collection equivalent and so can be counted directly:

| Method | Sites |
|---|---|
| `.setY(` | 31 |
| `.multiply(` | 22 |
| `.normalize(` | 15 |
| `.subtract(` | 12 |
| `.setX(` | 7 |
| `.setZ(` | 7 |
| **subtotal** | **94** |

Plus 8 `.add(` calls on genuinely vector- or location-typed receivers (`velocity`, `vel`, `loc`,
`groundLoc`, `locStanding`). A naive `.add(` grep is misleading here: most `.add(` calls in this
codebase are `List`/`Set` operations (`taskList`, `noJump`, `boats`, `standingOn`, `USERNAME_CACHE`),
not vector math.

Bukkit `Location` is mutable too, and `Location.add()` mutates in place, so location arithmetic
carries the identical hazard once translated to `Vec3`.

**Mitigation:** a small mutable `MotionVec` used internally, converted to `Vec3` only at the vanilla
boundary. This keeps the translation genuinely line-for-line and reduces 102 silent hazards to zero.
`MotionVec` mirrors Bukkit `Vector`'s semantics exactly and is unit-tested against them.

## 3. Architecture

### 3.1 Repository

Work happens on branch `neoforge-port`, cut from `master`. **The 1.21.1 Bukkit source stays on
`master` as the reference for every translated file.** `git show master:<path>` is the diff oracle
for faithfulness, and this is the primary defense against behavior drift.

The upstream project is EPL-2.0. This port is a derivative work: it remains under EPL-2.0 and
retains the upstream `LICENSE` and attribution.

### 3.2 Project layout

Single Gradle module. The API/Plugin split existed because the API module had to avoid CraftBukkit
types; with no bridge layer there is nothing left to separate.

```
terminator-plus/
├── gradle.properties            minecraft_version=26.2, neo_version=26.2.0.87, mod_id=tplus
├── build.gradle                 ModDevGradle 2.0.147, Java 25, unitTest enabled
├── settings.gradle
└── src/
    ├── main/java/net/nuggetmc/tplus/
    │   ├── TerminatorPlus.java          @Mod entry point
    │   ├── bot/                         Bot, BotConnection, BotFactory, BotRegistry,
    │   │                                BotGameProfiles, BotAdvancements
    │   ├── motion/                      MotionVec, BotPhysics, GroundCheck, BotMath
    │   ├── agent/                       Agent, AgentState
    │   │   └── legacy/                  LegacyAgent, SurroundingScan, Navigation,
    │   │                                Mining, BotBehaviors, Targeting, BlockRules
    │   ├── event/                       Bot lifecycle events on the NeoForge bus
    │   ├── command/                     Brigadier registration
    │   └── util/                        TickScheduler, MojangSkins
    │   └── gametest/                     @GameTestHolder classes — see note below
    ├── main/templates/META-INF/neoforge.mods.toml
    └── test/java/…                      JUnit + EphemeralTestServerProvider
```

**GameTests live in the main source set**, not a separate one. NeoForge discovers them by annotation
(`@GameTestHolder(MOD_ID)` on the class, `@GameTest` on methods) and enables them via the
`neoforge.enabledGameTestNamespaces` system property on the `gameTestServer` run configuration.
`src/main/java/…/gametest/` above is a package, not a source set.

A separate source set is possible, but requires explicitly binding it with
`neoForge { mods { tplus { sourceSet sourceSets.main; sourceSet sourceSets.gametest } } }` plus
`addModdingDependenciesTo sourceSets.gametest`. Not worth the configuration for this project.

**Accepted consequence:** GameTest classes ship inside the release jar. This is the standard NeoForge
tradeoff and is accepted deliberately rather than worked around.

**Version agility.** There is no `REQUIRED_VERSION` constant and no version-mismatch warning loop.
The Minecraft version appears only in `gradle.properties` and the generated `neoforge.mods.toml`.
Bumping to 26.3 should be a properties edit plus whatever genuinely broke. The current plugin's
hardcoded `REQUIRED_VERSION = "1.21.1"` and 20-line mismatch banner are exactly the pattern that
left it stranded, and are not carried forward.

### 3.3 Tick flow

Two tick sources, preserved from the original so ordering does not change:

```
ServerLevel entity tick (vanilla)
  └─ Bot.tick()
       ├─ loadChunks()
       ├─ super.tick()
       ├─ BotPhysics.step(...)  →  move(MoverType.SELF, vec)
       ├─ health regen
       ├─ Bot.fallDamageCheck()        (the entity-side check)
       └─ doTick()  →  detectEquipmentUpdates() + baseTick()

ServerTickEvent.Post
  └─ BotRegistry.tick()
       ├─ TickScheduler.drain(currentTick)
       └─ Agent.tick()  →  per bot: LegacyAgent.tickBot(bot)
                              ├─ Targeting.locate(bot)
                              ├─ Navigation.move(bot, target)
                              ├─ SurroundingScan.checkNearby(...)
                              └─ Mining / BotBehaviors
```

## 4. Component design

### 4.1 Bot core

| Class | Responsibility | Notes |
|---|---|---|
| `Bot extends ServerPlayer` | The bot entity | Overrides `hurtServer`, `die`, `tick`, `doTick`, `push`, and `isFakePlayer` (returns `false`). ~450 lines, down from 903. |
| `BotConnection extends Connection` | Fake network peer | ~15 lines. `super(PacketFlow.SERVERBOUND)`, no-op `setListenerForServerboundHandshake`, no-op `send` overloads, `isConnected()` returns `true`. Replaces `MockConnection` and `MockChannel`. |
| `BotGameProfiles` | Profile and skin construction | Static factory returning `GameProfile`. Required because `GameProfile` is now a final record. |
| `BotAdvancements` | Advancement no-op | Ported from NeoForge's `FakePlayerAdvancements`; prevents the #1487 leak. |
| `BotFactory` | Spawn and visibility | Sends player-info, add-entity, entity-data (via `getNonDefaultValues()`), and head-rotation packets. |
| `BotRegistry` | Live bot set and tick driver | Replaces `BotManagerImpl`. Concurrent-safe set, as today. |

`BotConnection` retains no-op `send` overrides because these bots genuinely enter the player list,
unlike NeoForge's `FakePlayer`, which never does. Whether a `MockChannel` replacement is needed at
all is an implementation-time question: it is kept initially and removed only if nothing reaches the
channel.

### 4.2 Platform services

Four things Bukkit provided that vanilla does not:

- **`TickScheduler`** — a tick-keyed task queue drained on `ServerTickEvent.Post`. Pure and
  unit-testable. This is **not a utility footnote**; it is on the critical path for roughly a third
  of the port. Bukkit scheduler touchpoints in v1 files:

  | Call | Sites |
  |---|---|
  | `runTaskLater` | **27** — `LegacyAgent` 14, `LegacyBlockCheck` 8, `Bot` 5 |
  | `runTaskAsynchronously` | 2 |
  | `new BukkitRunnable` | 2 |
  | `scheduleSyncRepeatingTask` | 1 |

  The delayed tasks cover shield blocking, render-on-login delay, death cleanup, bot removal, and
  mining animations. `TickScheduler` must support cancellation, because `Agent.stopAllTasks()` and
  `Mining.stopMining()` both rely on cancelling pending work.
- **Events** — the five custom Bukkit events (`BotDamageByPlayerEvent`, `BotDeathEvent`,
  `BotFallDamageEvent`, `BotKilledByPlayerEvent`, `TerminatorLocateTargetEvent`) become NeoForge bus
  events; `@EventHandler` listeners become `@SubscribeEvent`.
- **Commands** — Brigadier registered on `RegisterCommandsEvent`, gated with
  `requires(src -> src.hasPermission(2))`. This retires the roughly 370-line reflection-and-annotation
  command framework (`CommandHandler`, `CommandInstance`, `CommandMethod`, six annotation types,
  three exception types) and the `SimpleCommandMap` registration hack.
- **`BlockRules`** — replaces `LegacyMats` (489 lines of hand-maintained `Material` lists) with
  `BlockTags` lookups and `BlockState` property checks. Beyond being shorter, it stops going stale
  every version — the `Material.CHAIN` to `IRON_CHAIN` break is exactly the failure mode tags avoid.
  The cost is testability: tag membership is unavailable without a loaded datapack, so `BlockRules`
  is tested at the server-backed tier rather than in pure JUnit. See section 6.

### 4.3 Splitting LegacyAgent

`LegacyAgent` is 1,600 lines in very large methods: `checkNearby` is 280 lines, `checkUp` about 150,
`miscellaneousChecks` about 146, `locateTarget` about 133, `blockBreakEffect` about 117. The file's
own header comment reads "Yes, this code is very unoptimized, I know."

A line-by-line port of a 280-line method is unreviewable, and unreviewable is where behavior drift
hides. The strategy is **extract, do not rewrite**: method bodies move verbatim; only their address
changes.

| New class | Absorbs | ~LOC |
|---|---|---|
| `LegacyAgent` | `tick`, `tickBot`, `center`, `attack`, `LegacyAgent.fallDamageCheck` (the agent-side check, distinct from `Bot.fallDamageCheck`), the three event handlers, `stopAllTasks` | 350 |
| `SurroundingScan` | `checkNearby` — the 280-line method, alone in its own file | 280 |
| `Navigation` | `move`, `checkSide`, `checkUp`, `checkDown`, `swim` | 340 |
| `Mining` | `preBreak`, `blockBreakEffect`, `downMine`, `stopMining`, `placeWaterDown` | 240 |
| `BotBehaviors` | `miscellaneousChecks`, `resetHand`, `onBoat` | 190 |
| `Targeting` | `locateTarget`, `validateCloserEntity`, `getWeightedRegionDist`, region accessors | 180 |
| `BlockRules` | `checkSideBreak`, `checkFenceAndGates`, `checkObstacles`, `isDoorObstacle`, `checkAt`, plus `LegacyMats` | 150 |

**Shared state.** `LegacyAgent` holds twelve mutable collections — `noFace`, `noJump`, `slow`,
`miningAnim`, `boats`, `btList`, `btCheck`, `towerList`, `boatCooldown`, `crackList`, `mining`,
`fallDamageCooldown` — read and written across all of these concerns. Deciding per-class ownership
means guessing at sharing semantics, and guessing wrong changes behavior silently.

All twelve move as-is into a single `AgentState` object injected into each collaborator. Sharing
semantics stay bit-identical and the move is mechanical rather than a judgment call. Narrowing
ownership is deferred to a separate, deliberate change.

### 4.4 File disposition

Every file in the source tree, with its destination. Nothing is left implicit — an implementation
plan is written directly from this table.

**Bot core and platform** (from `TerminatorPlus-Plugin`)

| Source | LOC | Destination |
|---|---|---|
| `bot/Bot.java` | 903 | `bot/Bot` (~450) plus physics extracted to `motion/BotPhysics`, `motion/GroundCheck` |
| `bot/BotManagerImpl.java` | 278 | `bot/BotRegistry` |
| `TerminatorPlus.java` | 88 | `TerminatorPlus` (`@Mod` entry). Version-check block and `REQUIRED_VERSION` deleted |
| `nms/MockConnection.java` | 68 | `bot/BotConnection` — merged, reflection deleted |
| `nms/MockChannel.java` | 81 | `bot/BotConnection` — merged; deleted outright if nothing reaches the channel |
| `utils/NMSUtils.java` | 45 | **Deleted** — replaced by `entityData.getNonDefaultValues()` |
| `bridge/InternalBridgeImpl.java` | 18 | Folded into `bot/BotFactory` — no bridge layer needed |
| `utils/MCLogs.java` | 64 | `util/BotLog` — retarget to the mod's SLF4J logger |
| — | — | **New:** `bot/BotFactory`, `bot/BotGameProfiles`, `bot/BotAdvancements`, `util/TickScheduler`, `motion/MotionVec` |

**Agent** (from `TerminatorPlus-API`)

| Source | LOC | Destination |
|---|---|---|
| `agent/legacyagent/LegacyAgent.java` | 1600 | Split per section 4.3 into seven classes |
| `agent/legacyagent/LegacyMats.java` | 489 | `agent/legacy/BlockRules` (~150) via `BlockTags` |
| `agent/legacyagent/LegacyBlockCheck.java` | 287 | `agent/legacy/BlockScan` — a real `LegacyAgent` collaborator, constructed with `(LegacyAgent, Plugin)`. Holds 8 of the 27 `runTaskLater` sites |
| `agent/legacyagent/LegacyLevel.java` | 165 | `agent/legacy/ScanOffset` — the 3D offset enum (`ABOVE`, `BELOW`, `NORTH_U`, …) driving `SurroundingScan`. Offsets are `Vec3i` constants |
| `agent/Agent.java` | 90 | `agent/Agent` — `BukkitScheduler` fields replaced by `TickScheduler` |
| `agent/legacyagent/EnumTargetGoal.java` | 48 | `agent/legacy/TargetGoal` — verbatim |
| `agent/legacyagent/LegacyUtils.java` | 36 | `agent/legacy/LegacyUtils` |
| `agent/legacyagent/LegacyWorldManager.java` | 25 | `agent/legacy/LevelRules` |
| `agent/legacyagent/LegacyItems.java` | 9 | Folded into `agent/legacy/BlockRules` |
| — | — | **New:** `agent/AgentState` holding the twelve shared collections |

**Utilities and events** (from `TerminatorPlus-API`)

| Source | LOC | Destination |
|---|---|---|
| `utils/MathUtils.java` | 179 | `motion/BotMath` — `Vector` returns become `MotionVec`/`Vec3` |
| `utils/PlayerUtils.java` | 88 | `util/PlayerUtils` |
| `utils/ItemUtils.java` | 59 | `util/ItemUtils` |
| `utils/BotUtils.java` | 45 | `util/BotUtils` — `overlaps()` becomes `AABB.intersects` |
| `utils/MojangAPI.java` | 41 | `util/MojangSkins` — becomes `CompletableFuture` (section 7) |
| `utils/ChatUtils.java` | 36 | `util/Messages` — bungee `ChatColor` becomes `Component` + `ChatFormatting` |
| `utils/CustomGameProfile.java` | 31 | `bot/BotGameProfiles` — must become a factory; `GameProfile` is a final record |
| `utils/Singularity.java` | 26 | `util/Singularity` |
| `utils/DebugLogUtils.java` | 23 | `util/BotLog` — merged |
| `event/*.java` (5 files) | 176 | `event/` — NeoForge bus events |

**Commands** (from `TerminatorPlus-Plugin`)

| Source | LOC | Destination |
|---|---|---|
| `command/commands/BotCommand.java` | 557 | `command/BotCommands` — Brigadier tree for create, remove, list, configure |
| `command/commands/MainCommand.java` | 87 | `command/RootCommand` — Brigadier |
| `command/CommandInstance.java` | 265 | **Deleted** — Brigadier replaces it |
| `command/CommandHandler.java` | 132 | **Deleted** — Brigadier replaces it |
| `command/CommandMethod.java` | 60 | **Deleted** |
| `command/annotation/*` (6 files) | 72 | **Deleted** |
| `command/exception/*` (3 files) | 24 | **Deleted** — Brigadier exceptions |
| `command/nms/TPCommand.java` | 6 | **Deleted** |

**Deferred, not ported in v1** (remain on `master`)

`ai/IntelligenceAgent` (343), `commands/BotEnvironmentCommand` (323), `utils/Debugger` (497),
`commands/AICommand` (193), `botagent/BotAgent` (144), `ai/NeuralNetwork` (101),
`ai/NodeConnections` (69), `ai/BotData` (52), `botagent/BotSituation` (23), `ai/BotDataType` (18),
`botagent/VerticalDisplacement` (16), `ai/ActivationType` (9), `ai/BotNode` (8),
`agent/legacyagent/CustomListMode` (28).

**Public API, deferred with the API module**

`Terminator` (131), `BotManager` (59), `TerminatorPlusAPI` (22), `InternalBridge` (10),
`AIManager` (5).

**Internal bot interface.** The Bukkit-typed `Terminator` interface is deferred, but the agent still
needs a type to work against — `LegacyAgent.tickBot(...)` cannot take `Terminator`. In v1 the agent
operates on the concrete `Bot` class directly. Extracting a NeoForge-typed interface is deliberately
postponed until the public API is designed, so its shape is driven by real internal usage rather than
guessed up front.

## 5. Translation strategy

For each ported file:

1. Read the `master` original in full.
2. Translate types mechanically: `Location` to `Vec3` plus `ServerLevel`; `Vector` to `MotionVec`
   internally and `Vec3` at boundaries; `BoundingBox` to `AABB`; `Block` to `BlockPos` plus
   `BlockState`; `Material` to `Block` / `BlockState` / `BlockTags`; `Player` and `LivingEntity` to
   `ServerPlayer` and `LivingEntity`; `World` to `ServerLevel`.
3. Preserve control flow, magic numbers, and ordering exactly. Known bugs stay — `Bot.push()` has a
   copy-paste error using `getX()` and `getZ()` for both axes, and it is ported as-is. Fixing it is a
   separate, deliberate change with its own before-and-after test.
4. Diff the result against `git show master:<path>` and confirm every difference is an intended type
   translation.

## 6. Testing

Four layers, cheapest first.

**Pure JUnit** (no server). `MotionVec` semantics asserted against Bukkit `Vector`'s documented
behavior; `BotMath` yaw, pitch, offset, and clean; `TickScheduler` ordering, same-tick batching, and
cancellation; physics steps — friction, jump gating via `jumpTicks` and `groundTicks`, fall-damage
threshold, velocity clamping; targeting region-weight math.

`Vec3`, `AABB`, `Mth`, and `BlockPos` work here with **no bootstrap call** — verified empirically
against the 26.2 jar. They do require the full dependency classpath rather than the Minecraft jar
alone: `Vec3`'s static initializer references `io.netty.buffer.ByteBuf` through its `STREAM_CODEC`
field. ModDevGradle provides that classpath, so this costs nothing in practice.

**What cannot be tested at this tier — verified, not assumed.** `Bootstrap.bootStrap()` registers
blocks but does **not** load datapack tags. Running it and then querying a tag gives:

```
[bootstrapped]
  state    = Block{minecraft:oak_fence}[east=false,north=false,south=false,waterlogged=false,west=false]
  in FENCES= false
```

`oak_fence` is plainly in `BlockTags.FENCES`; the lookup returns `false` because tags load during
resource reload, which bootstrap does not perform. **Every `BlockTags` query returns false in a
plain JUnit environment.** `BlockRules` therefore cannot be unit-tested at this tier and is covered
at the server-backed tier instead.

This is a real cost of choosing tags over hardcoded lists: `LegacyMats`' `Material` sets were
trivially pure-testable, whereas tag membership is not. The tradeoff is accepted — tags are correct
at runtime and stop going stale every version, and the alternative (injecting a stubbed tag
predicate) would put a megamorphic call in `SurroundingScan`'s per-block, per-tick hot path.

**Server-backed JUnit.** `net.neoforged:testframework` with
`@ExtendWith(EphemeralTestServerProvider.class)` injects a real headless `MinecraftServer`, which
means loaded datapack tags. Covers the NMS layer: a bot spawns into the level; the fake connection
survives a tick without throwing; player-list add and remove are clean; removal leaves no residue.
Both the `addNewPlayer` and `addFreshEntity` paths are covered. **`BlockRules` is tested here**, for
the tag-loading reason above.

**GameTests.** Headless in CI via the `gameTestServer` run config. In-world behavior: a bot falls and
takes damage; jumps a one-block step; acquires and attacks a target; despawns cleanly.

> **Correction (2026-09-12, found executing Plan A).** Two claims above are wrong.
>
> 1. **The annotation-based GameTest tier does exist**, just not where this spec looked.
>    Minecraft 26.2 removed vanilla's `@GameTest`/`@GameTestHolder` in favour of a
>    datapack-driven `GameTestInstance` registry — but NeoForge's `testframework`
>    artifact ships its own `@GameTest` plus `@EmptyTemplate` (no `.nbt` files needed),
>    `ExtendedGameTestHelper`, and `GameTestPlayer`. Registration goes through a
>    `TestFramework` the mod builds with `FrameworkConfiguration.builder(...).create()`,
>    with `@ForEachTest`/`@TestHolder` holder classes, and `testframework` has to leave
>    `testImplementation` because GameTests run in-game.
>
> 2. **The server-backed JUnit tier cannot host in-world tests at all.**
>    `EphemeralTestServerProvider` builds a frozen, empty `LevelStem` registry — its
>    source comment reads "The server doesn't have any levels" and its javadoc says not
>    to touch the world and to use a GameTest if you need one. It is good for registry,
>    datapack and tag data, and for pure logic that needs those; it cannot construct an
>    entity, because that needs a `ServerLevel`.
>
> Consequence: anything involving a live bot — spawning, physics, `GroundCheck`,
> `BlockRules` — belongs in GameTests, not the server-backed tier. Plan A's `BotSpawnTest`
> is written and kept compiling but `@Disabled` pending that harness; the same ground is
> covered manually over RCON for now.

**Manual smoke testing.** Combat feel. No automated test captures whether a bot fights *well*, and
that is the project's whole point.

Enabled in `build.gradle` via:

```groovy
neoForge {
    unitTest {
        enable()
        testedMod = mods."${mod_id}"
    }
}
```

## 7. Error handling

Three deliberate deviations from the original. All are robustness rather than behavior change, and
each is called out in the commit that introduces it.

- **Per-bot tick isolation.** Today an exception inside `tickBot` propagates into the server tick.
  Each bot's tick is wrapped; failures are logged once per bot per failure class; a bot is evicted
  after three consecutive failed ticks. One misbehaving bot must not take the server down.
- **Skin fetch off the tick thread.** `MojangAPI.getSkin` performs blocking HTTP. It becomes a
  `CompletableFuture` with the result marshalled back via `server.execute()`. It must never block the
  server thread.
- **Main-thread removal**, as today — `BotRegistry` marshals off-thread removals via
  `server.execute()`.

## 8. Scope

### In v1

Bot entity and fake connection; spawn and client-visibility packets; `LegacyAgent` movement, combat,
targeting, and mining; `BlockRules`; `TickScheduler`; bot lifecycle events; and a Brigadier command
tree for create, remove, list, and configure.

Computed from the section 4.4 disposition: of 8,252 source lines, 1,824 are deferred features, 227
are the deferred public API, and 604 are deleted outright (the command framework and `NMSUtils`).
That leaves ~5,600 lines to translate, which shrinks by ~925 through `Bot`, `LegacyMats`, and the
connection classes, and grows by ~400 in new classes. **Roughly 5,000 LOC of v1 mod code.**

### Deferred

These remain on `master` and are ported in later work:

- The ML stack: `IntelligenceAgent`, `NeuralNetwork`, `NodeConnections`, `BotData`, `BotNode`,
  `BotDataType`, `ActivationType`, `AICommand`
- The work-in-progress second agent: `BotAgent`, `BotSituation`, `VerticalDisplacement`
- `BotEnvironmentCommand` and `Debugger` (497 lines)
- The published API artifact: `TerminatorPlusAPI`, `InternalBridge`, and the Bukkit-typed
  `Terminator` interface. A NeoForge-facing API is designed once the mod's own shape has settled.

### Explicitly not doing

Maintaining a parallel Paper build. Redesigning bot AI. Any client-side component.

## 9. Risks

1. **Bots inside the `PlayerList`.** `addNewPlayer` makes the server treat a bot as a real player —
   playerdata saves, chunk dispatch, keep-alives — all aimed at a fake connection. This is the least
   understood part of the port and the most likely source of surprises. *Mitigation:* the existing
   `addToPlayerList` toggle is retained and defaults to the safer `addFreshEntity` path; both paths
   get server-backed tests.
2. **`checkNearby`.** 280 lines of dense block-scanning logic translated once, with no reference
   implementation running side by side. *Mitigation:* isolated in its own file, diffed against
   `master` line by line, and covered by GameTests for the behaviors it drives.
3. **`Vec3` immutability.** 102 call sites where a discarded return value is a silent physics change
   (see section 2.5). *Mitigation:* `MotionVec`, unit-tested against Bukkit `Vector` semantics.
4. **Mod compatibility from `isFakePlayer()` returning `false`.** Automation mods that special-case
   fake players will treat bots as human. This is intended — protection and PvP mods commonly skip
   fake players entirely, which would break combat, the core feature. *Mitigation:* documented in the
   README; revisit if a concrete conflict is reported.
5. **26.3 lands during development.** 26.3-rc-2 released 2026-09-11. *Mitigation:* the version-agility
   measures in section 3.2; a bump is a properties edit plus real breakage only.

## 10. Open items

Deferred deliberately, each with a trigger for revisiting:

- **Group and package naming.** Currently `net.nuggetmc.tplus`, the upstream namespace. Revisit
  before any artifact is published under a different owner.
- **Distribution.** Modrinth and CurseForge listings, and whether releases are published at all.
  Revisit when v1 is feature-complete.
- **NeoForge-facing public API.** Deferred along with the rest of the API module; design once the
  mod's own shape has settled.
- **`MockChannel` replacement.** Kept initially; deleted if implementation shows nothing reaches the
  channel. Tracked in the section 4.4 disposition.
- **NeoForge-typed bot interface.** v1's agent works against the concrete `Bot` class. Extract an
  interface when the public API is designed, shaped by real internal usage.
