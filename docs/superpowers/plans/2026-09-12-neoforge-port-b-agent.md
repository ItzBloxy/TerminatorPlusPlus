# TerminatorPlus NeoForge Port — Plan B: The Agent

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bots that hunt. A bot spawned by Plan A stands still; a bot finished by Plan B locates a
target, closes on it, jumps gaps, swims, breaks blocks in its way, towers up, MLG-clutches out of a
fall, escapes lava in a boat, and hits what it is chasing.

**Architecture:** `LegacyAgent` is driven once per server tick from `BotRegistry.tickBot`. Its 1,600
lines are **extracted, not rewritten** — method bodies move verbatim and only their address changes
(spec §4.3). All twelve of its mutable collections move as-is into one injected `AgentState`, so
sharing semantics stay bit-identical. The remaining half of upstream `Bot.java` — the action and
animation API the agent calls every tick — lands first, because nothing else can be tested without it.

**Tech Stack:** Java 25, Gradle 9.2.1, ModDevGradle 2.0.147, NeoForge 26.2.0.87, Minecraft 26.2,
JUnit 6.1.3, NeoForge `testframework` + GameTests.

**Spec:** `docs/superpowers/specs/2026-09-12-terminatorplus-neoforge-port-design.md`

**Plan A:** `docs/superpowers/plans/2026-09-12-neoforge-port-a-foundation.md` — complete, on this
branch as of `3302430`.

**Branch:** `neoforge-port`. The Paper 1.21.1 source stays on `master`. Read any original with
`git show master:<path>` — this is the faithfulness oracle and you should use it constantly.

**Reference the patched jar, not a Paper jar.** Verify every vanilla signature against
`build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar`. During Plan A, checking APIs
against a Paper jar produced three wrong answers in a row, because Paper patches vanilla classes
(`detectEquipmentUpdates` is public there, private in vanilla). Unzip the sources jar once:

```bash
mkdir -p /tmp/mcsrc && cd /tmp/mcsrc && unzip -oq build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar
```

---

## Scope

Everything the spec lists under v1 that Plan A did not deliver:

- The rest of upstream `Bot.java` — the action API (`stand`, `sneak`, `swim`, `punch`, `look`,
  `faceLocation`, `setItem`, `attack`, `block`, `walk`, `attemptBlockPlace`) and the damage path
  (the `hurt` override, knockback, the two player-damage events).
- All seven classes `LegacyAgent` splits into, plus `BlockRules`, `ScanOffset`, `BlockScan`,
  `LegacyUtils`, `LevelRules`, `TargetGoal`, `Agent` and `AgentState`.
- The five bot lifecycle events.
- `util/BotLog`, `util/BotUtils`, `util/PlayerUtils`, `util/ItemUtils`.
- `PlayerList.placeNewPlayer` support — spec risk 1, which Plan A proved impossible the cheap way.

**Deferred, unchanged from the spec:** the neural-network AI (`IntelligenceAgent`, `NeuralNetwork`,
`BotData`, `BotNode`, `BotDataType`, `ActivationType`, `NodeConnections`, `BotAgent`, `BotSituation`,
`VerticalDisplacement`, `CustomListMode`), `Debugger`, `AICommand`, `BotEnvironmentCommand`, and the
public API module.

### Phases

Seven phases, each ending in a build that passes. Phase 4 is the milestone that matters: at the end
of it a bot hunts you. Phases 5-7 make it competent. Each phase's tasks depend only on earlier
phases, so a phase boundary is the natural place to stop, review, or split this document.

| Phase | Tasks | End state |
|---|---|---|
| 1. Bot action API | 1-5 | An operator-driven bot: punches, looks, sneaks, holds items, places blocks |
| 2. Events and damage | 6-8 | Shield blocking cancels damage; kills count; mobs ignore bots |
| 3. PlayerList | 9 | Bots can join the real `PlayerList`, or it is proven impossible with evidence |
| 4. **The bot hunts** | 10-12 | **Bots locate a target, close on it, swim, and hit it** |
| 5. Block rules | 13-15 | The `LegacyMats` predicate surface, tag-backed |
| 6. Mining | 16-19 | Bots break blocks with crack animation, mine down, tower up |
| 7. Scan and assembly | 20-25 | Full `tickBot` flow: obstacles, MLG, clutch, boats, lava; the whole command tree |

---

## Six spec corrections found while planning

Apply these. The spec predates them, and two change what gets written.

**1. `MCLogs` is not a logger. Delete it; do not port it.**

Spec §4.4 maps `utils/MCLogs.java` (64 lines) to `util/BotLog` — "retarget to the mod's SLF4J
logger". Reading it shows that is wrong. `MCLogs` is a **paste uploader**: it formats a server report
and POSTs it to `https://api.mclo.gs/1/log`. Two of its nine fields are
`TerminatorPlus.isCorrectVersion()` and `REQUIRED_VERSION` — the version-check machinery Plan A
deleted outright. Three more (`Bukkit.getTPS()`, `Bukkit.getName()`, the plugin list) have no vanilla
equivalent; NeoForge has no plugin list. Uploading server state to a third-party service is also not
something to port silently.

`BotLog` therefore comes from `DebugLogUtils` alone: log to the mod logger and to online operators.

**2. Only one of the five events is a bus event upstream.**

Spec §4.2 says all five "become NeoForge bus events". Four of them are not events at all — they are
plain value objects passed straight to a method on `Agent`:

| Event | Upstream dispatch |
|---|---|
| `BotFallDamageEvent` | `plugin.getManager().getAgent().onFallDamage(event)` — `Bot.java:362` |
| `BotDamageByPlayerEvent` | `agent.onPlayerDamage(event)` — `Bot.java:710` |
| `BotKilledByPlayerEvent` | `agent.onBotKilledByPlayer(...)` — `Bot.java:730` |
| `BotDeathEvent` | `agent.onBotDeath(...)` — `BotManagerImpl.java:265` |
| `TerminatorLocateTargetEvent` | `Bukkit.getPluginManager().callEvent(event)` — `LegacyAgent.java:1549` |

Putting the first four on a bus would add an extension point upstream does not have and change
dispatch order. They stay plain classes invoked as `agent.onXxx(...)`. Only
`TerminatorLocateTargetEvent` extends `net.neoforged.bus.api.Event` and implements
`ICancellableEvent`, preserving the one place a third party can retarget or veto.

`BotDeathEvent extends EntityDeathEvent` upstream to carry drops and dropped XP. Vanilla has no such
event, so it becomes a plain class holding the `Bot`, the `DamageSource` and a mutable
`List<ItemStack>` — the list `LegacyAgent.onBotDeath` clears when drops are disabled.

**3. `LegacyMats` does not reduce to ~150 lines.**

Spec §4.2 estimates `BlockRules` at ~150 lines, on the theory that `BlockTags` replaces hand-written
`Material` lists. That holds for the twelve set constants — `FENCE`, `GATES`, `LEAVES`, `OBSTACLES`
and friends genuinely collapse into tag lookups. It does not hold for the other half of the file:
`canPlaceWater` (60 lines), `canPlaceTwistingVines` (90) and `shouldReplace` (42) are dense
`BlockState`-property decision trees with no tag equivalent, and they are load-bearing for every MLG
clutch. Revised estimate: **~180 lines of rules plus ~170 lines of placement predicates**, split
across `BlockRules` and `BlockPlacement` (Tasks 13 and 14) so neither file is unreviewable.

**4. The neural-network branches are unreachable in v1. Omit them; do not translate them.**

`tickBot` and `move` both branch on `bot.hasNeuralNetwork()`, and those branches call
`NeuralNetwork`, `BotNode` and `BotData` — all deferred by spec §4.4. With the AI deferred,
`hasNeuralNetwork()` is permanently false and the branches are dead. Translating dead code against
absent types is impossible; translating it against stub types is worse, because the stub silently
becomes the specification. Each site gets a comment naming the upstream line range it omits and why.
`Bot.hasNeuralNetwork()` is not added at all, so the omission cannot drift into a silent behaviour
change — the code will not compile if someone half-restores it.

**5. Eight 26.2 renames the plan must use.**

Found by grepping the patched sources. Every one of them would have compiled as the wrong thing, or
not at all.

| 1.21.1 | 26.2 | Where it bites |
|---|---|---|
| constants on `EntityType` | constants moved to `net.minecraft.world.entity.EntityTypes` | `BotBehaviors` spawning the lava-escape boat |
| `ResourceLocation` | `net.minecraft.resources.Identifier` | anything registry-keyed |
| `Direction.step()` / `getNormal()` | `getUnitVec3()` / `getUnitVec3i()` | `look(Direction)`, `ScanOffset` |
| `SoundEvents.SHIELD_BLOCK` as `SoundEvent` | `Holder.Reference<SoundEvent>` | needs the `Holder<SoundEvent>` `playSound` overload |
| Bukkit `EntityType.BOAT`, one type | boats split per wood; `Boat` is `world.entity.vehicle.boat.Boat` | use `EntityTypes.OAK_BOAT`, matching upstream's oak boat item |
| `Entity.setRot(float,float)` public | `protected` | fine from inside `Bot`; not callable from `Navigation` |

The seventh lands on the op check `BotLog` needs: `PlayerList.isOp` now takes a `NameAndId`, not a
`GameProfile`. `Player.nameAndId()` produces one, so the call is
`server.getPlayerList().isOp(player.nameAndId())`.

The eighth is the widest: **variant families are no longer individual `Blocks` constants.** This is
the same breakage the spec cites as the argument for tags, and it bites this plan in three places:

| Looks like | Actually |
|---|---|
| `Blocks.CHAIN` | **`Blocks.IRON_CHAIN`** — literally the `Material.CHAIN` to `IRON_CHAIN` rename spec §4.2 names |
| `Blocks.WHITE_CARPET`, `Blocks.WHITE_STAINED_GLASS_PANE` | `Blocks.CARPET.pick(DyeColor.WHITE)`, `Blocks.STAINED_GLASS_PANE.pick(DyeColor.WHITE)` — dyed families are a `ColorCollection<Block>` |
| `Blocks.LIGHTNING_ROD` | a `WeatheringCopperCollection<Block>` — rods weather like copper, so identity comparison does not compile. Use `instanceof LightningRodBlock` |

Wherever a rule can be expressed as a tag it is immune to all three, which is the whole argument for
§4.2's tag strategy — `BlockRules` survives these renames, the explicit sets inside it do not.

Two things that are unchanged but easy to get wrong: `Entity.getBoundingBox()` is `public final`, so
`getBotBoundingBox()` is a pass-through and **not** an override; and
`LivingEntity.setItemSlot(EquipmentSlot.MAINHAND, …)` is correct for players, because
`PlayerEquipment.set` routes `MAINHAND` to `inventory.setSelectedItem`.

**6. Four things spec §4.4 sends to v1 have no v1 caller. Do not create them.**

Found by grepping every call site on `master` (`git grep -n 'ClassName\.' master -- '*.java'`) and
crossing it against the deferred list. An empty class is worse than an absent one: it looks like a
seam, and the next person wires something into it.

| Spec §4.4 says | Upstream callers | Verdict |
|---|---|---|
| `utils/ChatUtils` → `util/Messages` | `CommandHandler`, `CommandInstance` (both deleted), `IntelligenceAgent`/`NeuralNetwork` (deferred); `trim16` already lives in `BotGameProfiles` | **Not created.** `BotCommands` builds `Component` directly |
| `utils/Singularity` → `util/Singularity` | none anywhere in the repo | **Not created** |
| `PlayerUtils.randomName`, `fillUsernameCache`, `findBottom` | none; `findAbove` only from `IntelligenceAgent` (deferred) | **Not ported.** `PlayerUtils` keeps `isInvincible` alone |
| `BotUtils.overlaps` | `Bot.isFallBlocked` and `checkStandingOn`, both already ported in Plan A using `AABB.intersects` directly | **Not ported** — a one-line alias for a vanilla method |

One disposition in the same table is simply mis-filed rather than unnecessary: spec §4.4 sends
`LegacyItems` (the three iron tools `preBreak` chooses between) into `BlockRules`. Those are
**items**, and a block-rules class is the wrong home. They live in `Mining.TOOLS` instead, next to
the only code that reads them (Task 16).

`BotLog` survives this cut, but only just: its single upstream caller (`CommandHandler`) is deleted
too. It is worth keeping because the dual output — server log *and* online operators — is the only
way an operator standing next to a misbehaving bot sees anything, and Plan A's three-strike eviction
message currently goes to the log alone. Task 1 therefore ports `BotLog` faithfully **and** routes
that one existing message through it. That added line is the only behaviour this plan adds rather
than translates; it is flagged again at the call site.

---

## File Structure

Files created or modified by this plan. One responsibility each. The two largest,
`SurroundingScan` and `Mining`, are each a single upstream method left alone in its own file rather
than split — a 280-line method is unreviewable if it is also interleaved with other concerns.

**New: platform and utilities**

| File | Responsibility |
|---|---|
| `src/main/java/net/nuggetmc/tplus/util/BotLog.java` | Debug log to console and online operators (was `DebugLogUtils`) |
| `src/main/java/net/nuggetmc/tplus/util/BotUtils.java` | `NO_FALL` (moved out of `Bot`) and `getHorizSqDist` |
| `src/main/java/net/nuggetmc/tplus/util/ItemUtils.java` | `getLegacyAttackDamage` — the 1.8 damage table |
| `src/main/java/net/nuggetmc/tplus/util/PlayerUtils.java` | `isInvincible` — see correction 6 for what is left out |

**New: events**

| File | Responsibility |
|---|---|
| `src/main/java/net/nuggetmc/tplus/event/BotDamageByPlayerEvent.java` | Value object; cancellable, mutable damage |
| `src/main/java/net/nuggetmc/tplus/event/BotFallDamageEvent.java` | Value object; cancellable, carries `standingOn` |
| `src/main/java/net/nuggetmc/tplus/event/BotKilledByPlayerEvent.java` | Value object |
| `src/main/java/net/nuggetmc/tplus/event/BotDeathEvent.java` | Value object; mutable drop list |
| `src/main/java/net/nuggetmc/tplus/event/TerminatorLocateTargetEvent.java` | The one real bus event; `ICancellableEvent` |

**New: agent**

| File | Responsibility | ~LOC |
|---|---|---|
| `src/main/java/net/nuggetmc/tplus/agent/Agent.java` | Base: enable/disable, task list, the four event hooks | 90 |
| `src/main/java/net/nuggetmc/tplus/agent/AgentState.java` | The twelve shared mutable collections | 70 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` | `tick`, `tickBot`, `center`, `attack`, `fallDamageCheck`, handlers, `stopAllTasks` | 350 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/Targeting.java` | `locateTarget`, `validateCloserEntity`, region weighting | 200 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/TargetGoal.java` | The eleven goals, verbatim | 50 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java` | `move`, `swim`, `checkSide`, `checkUp`, `checkDown` | 340 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/SurroundingScan.java` | `checkNearby` — the 280-line method, alone | 300 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/Mining.java` | `preBreak`, `blockBreakEffect`, `downMine`, `stopMining`, `placeWaterDown` | 260 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/BotBehaviors.java` | `miscellaneousChecks`, `resetHand`, `onBoat` | 200 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockScan.java` | `placeBlock`, `placeFinal`, `tryPreMLG`, `clutch` | 240 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockRules.java` | Tag- and state-backed replacement for `LegacyMats`' sets | 180 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockPlacement.java` | `canPlaceWater`, `canPlaceTwistingVines`, `shouldReplace` | 170 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/ScanOffset.java` | The 24-way 3D offset enum (was `LegacyLevel`) | 150 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyUtils.java` | `checkFreeSpace` ray march, break sound | 50 |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/LevelRules.java` | `aboveGround` | 30 |

**Modified**

| File | Change |
|---|---|
| `src/main/java/net/nuggetmc/tplus/bot/Bot.java` | The action API, the `hurtServer` override, knockback, event firing |
| `src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java` | Owns the agent; `tickBot` dispatches to it; death and mob-target hooks |
| `src/main/java/net/nuggetmc/tplus/bot/BotFactory.java` | The `placeNewPlayer` path replaces the `UnsupportedOperationException` |
| `src/main/java/net/nuggetmc/tplus/TerminatorPlus.java` | Registers the player-login and mob-target listeners |
| `src/main/java/net/nuggetmc/tplus/command/BotCommands.java` | `goal`, `region`, `drops`, `shield`, `agent`, and the action subcommands |

`MotionVec` needs no new members. The only upstream call it lacks is `rotateAroundY`, and its two
call sites are both inside `move`'s neural-network branch, which correction 4 omits.

**Tests**

| File | Tier |
|---|---|
| `src/test/java/net/nuggetmc/tplus/util/ItemUtilsTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/util/BotUtilsTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/agent/AgentStateTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/agent/legacy/ScanOffsetTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/agent/legacy/TargetGoalTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/agent/legacy/RegionWeightTest.java` | Pure |
| `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java` | In-world |
| `src/gametest/java/net/nuggetmc/tplus/gametest/BotCombatTests.java` | In-world |
| `src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java` | In-world (tags need a loaded datapack) |
| `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java` | In-world |

---

## Translation reference

Consult this instead of re-deriving the mapping in every task.

| Bukkit | 26.2 |
|---|---|
| `Location` | `Vec3` plus the `ServerLevel` passed alongside |
| `Location.getBlock()` | `level.getBlockState(BlockPos.containing(vec))` |
| `Location.getBlockX()` | `Mth.floor(vec.x)`, or `BlockPos.containing(vec).getX()` |
| `Vector` | `MotionVec` internally, `Vec3` at boundaries |
| `BoundingBox` | `AABB` |
| `Block` | `BlockPos` plus `BlockState` — never one without the other |
| `Material` | `Block`, `BlockState`, or a `TagKey<Block>` |
| `BlockFace` | `net.minecraft.core.Direction`; its unit vector is `getUnitVec3()` |
| `BlockData` property read | `state.getValue(BlockStateProperties.X)` / `getValueOrElse(…, default)` |
| `block.setType(m)` | `level.setBlockAndUpdate(pos, block.defaultBlockState())` |
| `block.breakNaturally()` | `level.destroyBlock(pos, true, bot)` |
| `block.getDestroySpeed(tool)` | `tool.getDestroySpeed(state)` |
| `getBlockData().getSoundGroup().getBreakSound()` | `state.getSoundType().getBreakSound()` |
| `world.playSound(loc, s, v, p)` | `level.playSound(null, pos, s, SoundSource.BLOCKS, v, p)` |
| `Player` / `LivingEntity` | `ServerPlayer` / `LivingEntity` (vanilla) |
| `player.getFacing()` | `entity.getDirection()` |
| `player.getEyeLocation()` | `entity.getEyePosition()` |
| `entity.getNoDamageTicks()` | `entity.invulnerableTime` |
| `((Damageable) e).damage(d, src)` | `e.hurtServer(level, source, (float) d)` |
| `GameMode` | `GameType`; a `ServerPlayer`'s is `player.gameMode.getGameMode()` |
| `Bukkit.getOnlinePlayers()` | `server.getPlayerList().getPlayers()`, filtered to exclude `Bot` |
| `world.getLivingEntities()` | `level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> true)` |
| `BukkitRunnable` + `runTaskLater` | `TickScheduler.runLater(delay, action)`, returning a cancellable id |
| `runTaskTimer(plugin, 0, n)` | `TickScheduler.runRepeating(n, action)` — added in Task 17 |
| `ItemStack` (Bukkit) | `net.minecraft.world.item.ItemStack`; empty is `ItemStack.EMPTY`, never `null` |

## GameTest conventions

Two things that silently produce a green run if you get them wrong. Both were found executing
Task 2, and every GameTest in this plan is written assuming them.

**Every test method needs `@TestHolder("<id>")`, not just `@GameTest`.** The NeoForge test
framework collects tests by their holder id; a `@GameTest` method without one is never registered,
and the run still prints `All N required tests passed` with the old N. Plan A's suite is 16
`@GameTest` and 16 `@TestHolder`, exactly 1:1. After adding tests, check the count moved:

```bash
./gradlew runGameTestServer 2>&1 | grep -E "Found [0-9]+ tests|required tests"
```

The id convention in this plan is the method name verbatim, so the two read the same.

**`assertValueEqual` is `equals` on a boxed value, not numeric comparison.** For floats that means
bit-pattern equality: `-0.0f` does not equal `0.0f`, and `NaN` does not equal itself. A flat
direction gives `BotMath.fetchPitch` a pitch of `-0.0`, which is correct and which
`assertValueEqual(pitch, 0f, …)` rejects. Use primitive `==` inside `assertTrue` for any float that
can legitimately be signed zero, and an epsilon for anything computed through trigonometry.

---

Two hazards worth repeating from Plan A, because both are live again here:

- **`MotionVec` mutates in place; `Vec3` does not.** Every `MotionVec` mutator returns `this`, so
  `a.add(b)` changes `a`. `Vec3.add` returns a new vector and changes nothing. A translation that
  swaps one for the other compiles silently and changes physics.
- **`normalize()` on a zero vector yields `NaN, NaN, NaN`**, deliberately, because upstream's
  `clean()` and `isNotFinite()` guards exist to catch exactly that. Do not "fix" it.

---
# Phase 1: The bot action API

Upstream `Bot.java` is 903 lines. Plan A ported roughly 450 of them — construction, ticking,
physics, damage, death. The rest is the **action API**: the methods the agent calls on a bot every
tick to make it look somewhere, hold something, crouch, swing, or move. None of the agent can be
tested until these exist, so they land first, driven by hand from `/tplus` before any AI exists.

## Task 1: Utilities

Four small files, and the removal of two things that turned out to have no caller (correction 6).
`NO_FALL` moves out of `Bot` rather than being duplicated — it is the same list, and `BlockScan`
will need it in Phase 7.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/util/BotLog.java`
- Create: `src/main/java/net/nuggetmc/tplus/util/BotUtils.java`
- Create: `src/main/java/net/nuggetmc/tplus/util/ItemUtils.java`
- Create: `src/main/java/net/nuggetmc/tplus/util/PlayerUtils.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java` — delete the private `NO_FALL` set, use `BotUtils.NO_FALL`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java` — route the eviction message through `BotLog`
- Test: `src/test/java/net/nuggetmc/tplus/util/BotUtilsTest.java`
- Test: `src/test/java/net/nuggetmc/tplus/util/ItemUtilsTest.java`

Read the originals first:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/BotUtils.java
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/ItemUtils.java
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/DebugLogUtils.java
```

- [ ] **Step 1: Write the failing pure test for `BotUtils`**

`src/test/java/net/nuggetmc/tplus/util/BotUtilsTest.java`:

```java
package net.nuggetmc.tplus.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure: BlockPos and Vec3 are plain data classes with no registry dependency.
 */
class BotUtilsTest {

    @Test
    void horizontalDistanceIsMeasuredFromTheBlockCentre() {
        // Upstream added 0.5 to the block coordinate because a Block's Location is its
        // minimum corner. A bot standing dead centre on a block is at distance 0, not
        // 0.5 — every sort that orders candidate blocks by proximity depends on this.
        assertEquals(0.0, BotUtils.getHorizSqDist(new BlockPos(10, 64, 20), new Vec3(10.5, 64, 20.5)));
    }

    @Test
    void horizontalDistanceIgnoresY() {
        double flat = BotUtils.getHorizSqDist(new BlockPos(0, 64, 0), new Vec3(3.5, 64, 0.5));
        double high = BotUtils.getHorizSqDist(new BlockPos(0, 64, 0), new Vec3(3.5, 200, 0.5));

        assertEquals(flat, high, "the name says horizontal; Y must not contribute");
        assertEquals(9.0, flat);
    }

    @Test
    void horizontalDistanceIsSquaredNotRooted() {
        // 3 blocks away squares to 9. Callers only ever compare, so upstream never took
        // the root; a port that "helpfully" returns the real distance changes nothing
        // visible but makes every comparison against a squared constant wrong.
        assertEquals(9.0, BotUtils.getHorizSqDist(new BlockPos(3, 0, 0), new Vec3(0.5, 0, 0.5)));
    }

    @Test
    void noFallListedTheBlocksThatCancelFallDamage() {
        assertTrue(BotUtils.NO_FALL.contains(net.minecraft.world.level.block.Blocks.WATER));
        assertTrue(BotUtils.NO_FALL.contains(net.minecraft.world.level.block.Blocks.COBWEB));
        assertTrue(BotUtils.NO_FALL.contains(net.minecraft.world.level.block.Blocks.POWDER_SNOW));
        assertEquals(10, BotUtils.NO_FALL.size(), "upstream BotUtils.NO_FALL had exactly 10 entries");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.util.BotUtilsTest'
```

Expected: compile failure, `cannot find symbol: class BotUtils`.

Note the fourth test touches `Blocks`, which forces registry static init. If it throws
`NullPointerException` or `IllegalStateException` from inside `BuiltInRegistries` rather than
failing the assertion, the source set cannot bootstrap the game — jump to Step 4 and keep only the
first three tests here, moving the `NO_FALL` assertions into the GameTest added in Task 5. Plan A
hit exactly this wall with `EphemeralTestServerProvider`; do not fight it.

- [ ] **Step 3: Write `BotUtils`**

`src/main/java/net/nuggetmc/tplus/util/BotUtils.java`:

```java
package net.nuggetmc.tplus.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Ported from {@code api/utils/BotUtils}.
 *
 * <p>{@code randomSteveUUID} moved to {@link net.nuggetmc.tplus.bot.BotGameProfiles} in Plan A,
 * and {@code overlaps} is not ported: it was a one-line alias for {@code BoundingBox.overlaps},
 * and both of its callers now use {@code AABB.intersects} directly.
 */
public final class BotUtils {

    /**
     * Blocks that cancel fall damage. Ported from {@code BotUtils.NO_FALL}; the Paper build
     * listed Materials, these are the equivalent Blocks.
     *
     * <p>Lived in {@code Bot} through Plan A, which had the only caller. Phase 7's
     * {@code BlockScan} needs it too, so it moves to its upstream address.
     */
    public static final Set<Block> NO_FALL = Set.of(
            Blocks.WATER, Blocks.LAVA,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
            Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
            Blocks.COBWEB, Blocks.VINE);

    private BotUtils() {
    }

    /**
     * Squared horizontal distance from the centre of {@code blockPos} to {@code pos}.
     *
     * <p>The {@code + 0.5} is upstream's and is load-bearing: a Bukkit Block's Location is its
     * minimum corner, so without it every proximity sort is biased half a block. Callers only
     * compare results, so the root is never taken.
     */
    public static double getHorizSqDist(BlockPos blockPos, Vec3 pos) {
        double dx = blockPos.getX() + 0.5 - pos.x;
        double dz = blockPos.getZ() + 0.5 - pos.z;

        return dx * dx + dz * dz;
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.util.BotUtilsTest'
```

Expected: 4 tests, 0 failures.

- [ ] **Step 5: Write the failing test for `ItemUtils`**

`src/test/java/net/nuggetmc/tplus/util/ItemUtilsTest.java`:

```java
package net.nuggetmc.tplus.util;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Needs the registries, because the damage table is keyed by {@code Item} and {@code Items}
 * cannot class-initialise without them. This is the first bootstrapped unit test in the
 * project; everything in Plan A was pure.
 */
class ItemUtilsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theDamageTableIsThe18Table() {
        // Upstream's whole point: a bot hits for 1.8 damage values regardless of what the
        // current game says a diamond sword does. These five pin the ends and the middle.
        assertEquals(8.0, ItemUtils.getLegacyAttackDamage(new ItemStack(Items.NETHERITE_SWORD)));
        assertEquals(7.0, ItemUtils.getLegacyAttackDamage(new ItemStack(Items.DIAMOND_SWORD)));
        assertEquals(4.0, ItemUtils.getLegacyAttackDamage(new ItemStack(Items.WOODEN_SWORD)));
        assertEquals(3.0, ItemUtils.getLegacyAttackDamage(new ItemStack(Items.IRON_SHOVEL)));
        assertEquals(1.0, ItemUtils.getLegacyAttackDamage(new ItemStack(Items.NETHERITE_HOE)));
    }

    @Test
    void anUnlistedItemDealsTheFistDamage() {
        // Upstream's `default: return 0.25`. A bot with nothing in hand still does something,
        // and this is the value the agent relies on for a bare-handed bot.
        assertEquals(0.25, ItemUtils.getLegacyAttackDamage(new ItemStack(Items.COBBLESTONE)));
        assertEquals(0.25, ItemUtils.getLegacyAttackDamage(new ItemStack(Items.DIAMOND_HELMET)));
    }

    @Test
    void anEmptyStackDealsTheFistDamage() {
        // The Paper build initialised defaultItem to ItemStack(Material.AIR) and passed it
        // straight in. The vanilla equivalent is ItemStack.EMPTY, whose item is Items.AIR —
        // unlisted, so 0.25. Pinned because a null-check here would mask a real bug instead.
        assertEquals(0.25, ItemUtils.getLegacyAttackDamage(ItemStack.EMPTY));
    }

    @Test
    void aStoneAxeAndAnIronPickaxeAgree() {
        // Upstream grouped these two on the same case label. Easy to lose when a switch on
        // an enum becomes a map of thirty entries, and nothing else would notice.
        assertEquals(ItemUtils.getLegacyAttackDamage(new ItemStack(Items.STONE_AXE)),
                ItemUtils.getLegacyAttackDamage(new ItemStack(Items.IRON_PICKAXE)));
        assertEquals(4.0, ItemUtils.getLegacyAttackDamage(new ItemStack(Items.STONE_AXE)));
    }
}
```

- [ ] **Step 6: Run it and confirm it fails**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.util.ItemUtilsTest'
```

Expected: compile failure, `cannot find symbol: class ItemUtils`.

If instead `Bootstrap.bootStrap()` throws, the unit-test source set cannot load the game. In that
case delete this file, put the same five assertions in `BotActionTests` (Task 5) where a real server
is running, and record the reason in a comment there. Do not spend time making bootstrap work.

- [ ] **Step 7: Write `ItemUtils`**

`src/main/java/net/nuggetmc/tplus/util/ItemUtils.java`:

```java
package net.nuggetmc.tplus.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;

import static java.util.Map.entry;

/**
 * The 1.8 attack-damage table, ported verbatim from {@code api/utils/ItemUtils}.
 *
 * <p>Upstream switched on Bukkit's {@code Material} enum. Vanilla {@code Item} is not an enum,
 * so the switch becomes a lookup table; the grouping and the values are unchanged, including
 * the {@code 0.25} fallback for anything unlisted.
 *
 * <p>This deliberately ignores the item's real {@code ATTACK_DAMAGE} attribute. Bots are meant
 * to hit like a 1.8 player, and that is the behaviour being preserved.
 */
public final class ItemUtils {

    private static final double FIST = 0.25;

    private static final Map<Item, Double> LEGACY_DAMAGE = Map.ofEntries(
            entry(Items.WOODEN_SHOVEL, 1.0),
            entry(Items.GOLDEN_SHOVEL, 1.0),
            entry(Items.WOODEN_HOE, 1.0),
            entry(Items.GOLDEN_HOE, 1.0),
            entry(Items.STONE_HOE, 1.0),
            entry(Items.IRON_HOE, 1.0),
            entry(Items.DIAMOND_HOE, 1.0),
            entry(Items.NETHERITE_HOE, 1.0),

            entry(Items.WOODEN_PICKAXE, 2.0),
            entry(Items.GOLDEN_PICKAXE, 2.0),
            entry(Items.STONE_SHOVEL, 2.0),

            entry(Items.WOODEN_AXE, 3.0),
            entry(Items.GOLDEN_AXE, 3.0),
            entry(Items.STONE_PICKAXE, 3.0),
            entry(Items.IRON_SHOVEL, 3.0),

            entry(Items.WOODEN_SWORD, 4.0),
            entry(Items.GOLDEN_SWORD, 4.0),
            entry(Items.STONE_AXE, 4.0),
            entry(Items.IRON_PICKAXE, 4.0),
            entry(Items.DIAMOND_SHOVEL, 4.0),

            entry(Items.STONE_SWORD, 5.0),
            entry(Items.IRON_AXE, 5.0),
            entry(Items.DIAMOND_PICKAXE, 5.0),
            entry(Items.NETHERITE_SHOVEL, 5.0),

            entry(Items.IRON_SWORD, 6.0),
            entry(Items.DIAMOND_AXE, 6.0),
            entry(Items.NETHERITE_PICKAXE, 6.0),

            entry(Items.DIAMOND_SWORD, 7.0),
            entry(Items.NETHERITE_AXE, 7.0),

            entry(Items.NETHERITE_SWORD, 8.0));

    private ItemUtils() {
    }

    public static double getLegacyAttackDamage(ItemStack stack) {
        return LEGACY_DAMAGE.getOrDefault(stack.getItem(), FIST);
    }
}
```

- [ ] **Step 8: Run the test and confirm it passes**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.util.ItemUtilsTest'
```

Expected: 4 tests, 0 failures.

- [ ] **Step 9: Write `PlayerUtils` and `BotLog`**

`src/main/java/net/nuggetmc/tplus/util/PlayerUtils.java`:

```java
package net.nuggetmc.tplus.util;

import net.minecraft.world.level.GameType;

/**
 * Ported from {@code api/utils/PlayerUtils}, which had five methods. Four are not ported:
 * {@code randomName} and {@code fillUsernameCache} have no caller anywhere on {@code master},
 * {@code findBottom} likewise, and {@code findAbove}'s only caller is {@code IntelligenceAgent},
 * deferred with the neural-network AI. See plan correction 6.
 */
public final class PlayerUtils {

    private PlayerUtils() {
    }

    /**
     * True when a player in this mode cannot be hurt.
     *
     * <p>Verbatim from upstream, including the null branch: a null mode counts as
     * vulnerable, not invincible. {@code Targeting} calls this for every candidate every
     * tick, so the null case is reachable during a gamemode change.
     */
    public static boolean isInvincible(GameType mode) {
        return mode != GameType.SURVIVAL && mode != GameType.ADVENTURE && mode != null;
    }
}
```

`src/main/java/net/nuggetmc/tplus/util/BotLog.java`:

```java
package net.nuggetmc.tplus.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.bot.Bot;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Debug output that reaches both the server log and whoever is standing there.
 *
 * <p>Ported from {@code api/utils/DebugLogUtils}. Its one upstream caller
 * ({@code CommandHandler}) is deleted along with the reflection command framework, so see
 * plan correction 6 for why this is kept anyway.
 *
 * <p>{@code MCLogs} is deliberately not merged in here despite what spec §4.4 says: it is a
 * mclo.gs paste uploader wrapped around the deleted version check, not a logger.
 */
public final class BotLog {

    private static final String PREFIX = "[DEBUG] ";

    private BotLog() {
    }

    /**
     * Logs {@code values}, space-joined, to the mod logger and to every online operator.
     *
     * <p>{@code server} may be null — during shutdown, and in a unit test — in which case
     * only the logger is used.
     */
    public static void debug(MinecraftServer server, Object... values) {
        String message = join(values);

        TerminatorPlus.LOGGER.info("{}{}", PREFIX, message);

        if (server == null) {
            return;
        }

        Component component = Component.literal(PREFIX)
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(message).withStyle(ChatFormatting.RESET));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Bots are ServerPlayers too, and one in the PlayerList would otherwise get sent
            // chat down a connection that goes nowhere. Upstream had the same hole; skipping
            // them costs nothing and matches BotFactory.broadcast.
            if (player instanceof Bot) {
                continue;
            }

            // 26.2 moved the op check: PlayerList.isOp takes a NameAndId, not a GameProfile.
            // Player.nameAndId() builds one from the profile.
            if (server.getPlayerList().isOp(player.nameAndId())) {
                player.sendSystemMessage(component);
            }
        }
    }

    static String join(Object[] values) {
        return Arrays.stream(values).map(String::valueOf).collect(Collectors.joining(" "));
    }
}
```

- [ ] **Step 10: Point `Bot` at the shared `NO_FALL` and `BotRegistry` at `BotLog`**

In `src/main/java/net/nuggetmc/tplus/bot/Bot.java`, delete the private `NO_FALL` field and its
javadoc entirely, drop the now-unused `Blocks` import if nothing else uses it, and change the one
reference inside `isFallBlocked`:

```java
                Block block = state.getBlock();
                if (!BotUtils.NO_FALL.contains(block)) {
                    continue;
                }
```

Add `import net.nuggetmc.tplus.util.BotUtils;`.

In `src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java`, replace the second logger call in
`noteTickFailure`:

```java
        if (count >= MAX_CONSECUTIVE_FAILURES) {
            // The one line this plan adds rather than translates: an operator watching a bot
            // die repeatedly could not previously see why without reading the server log.
            BotLog.debug(server,
                    "Evicting bot '" + bot.getGameProfile().name() + "' after " + count
                            + " consecutive failures");
            safeRemove(bot);
        }
```

`BotRegistry` has no `MinecraftServer` field, and `noteTickFailure` is called from the entity tick,
so take it from the bot: `MinecraftServer server = bot.level().getServer();`. Add
`import net.minecraft.server.MinecraftServer;` and `import net.nuggetmc.tplus.util.BotLog;`.

- [ ] **Step 11: Build and run the whole suite**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`, 54 tests, 0 failures (46 from Plan A plus 8 new).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/util src/main/java/net/nuggetmc/tplus/bot src/test/java/net/nuggetmc/tplus/util
git commit -m "feat: port BotUtils, ItemUtils, PlayerUtils and BotLog"
```

---

## Task 2: Rotation and looking

Four methods, and they are the ones everything else depends on: a bot that cannot turn cannot mine
the right block, attack, or place a clutch. Two rotation channels have to stay in sync — the body
yaw (`setRot`) and the head yaw, which is a separate packet clients use for the head and the name
tag.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java` (new)

Read the original first — all four are in one block:

```bash
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java | sed -n '766,806p'
```

- [ ] **Step 1: Write the failing GameTests**

`src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java`:

```java
package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;

/**
 * In-world tests for the bot action API.
 *
 * <p>These cannot be unit tests: every one of them either sends a packet, reads a
 * {@code BlockState}, or asks the level for an entity, and Plan A established that
 * {@code EphemeralTestServerProvider} has no levels at all.
 *
 * <p>Positions are relative to the test structure; {@code helper.absolutePos} converts.
 */
@ForEachTest(groups = BotActionTests.GROUP)
public final class BotActionTests {

    public static final String GROUP = "bot.actions";

    private BotActionTests() {
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relative));

        return BotFactory.spawn(registry, level, pos, 0f, 0f,
                BotGameProfiles.create("ActionBot", null), false);
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void look_down_pitches_to_90_and_keeps_yaw(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setYRot(37f);

        bot.look(Direction.DOWN);

        helper.assertValueEqual(bot.getXRot(), 90f, "pitch after looking down");
        helper.assertValueEqual(bot.getYRot(), 37f,
                "yaw must survive a vertical look — upstream passed keepYaw for UP and DOWN");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void look_up_pitches_to_minus_90(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.look(Direction.UP);

        helper.assertValueEqual(bot.getXRot(), -90f, "pitch after looking up");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void look_north_sets_yaw_and_leaves_pitch_level(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setXRot(45f);

        bot.look(Direction.NORTH);

        // A horizontal look does NOT keep yaw, so both channels are recomputed. North is
        // yaw 180 in Minecraft's convention, and a flat direction gives pitch 0.
        helper.assertValueEqual(bot.getYRot(), 180f, "yaw after looking north");
        helper.assertValueEqual(bot.getXRot(), 0f, "pitch after a horizontal look");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void face_location_turns_the_head_too(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        Vec3 east = Vec3.atCenterOf(helper.absolutePos(new BlockPos(6, 1, 1)));
        bot.faceLocation(east);

        // Body yaw and head yaw must agree: the client draws the head from yHeadRot and the
        // body from yRot, and upstream sent ClientboundRotateHeadPacket for exactly this.
        helper.assertValueEqual(bot.getYHeadRot(), bot.getYRot(), "head yaw must follow body yaw");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void face_location_at_own_position_does_not_produce_nan(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        float before = bot.getYRot();

        // A zero direction vector normalises to NaN by design (see the plan's hazard note).
        // setRot would then poison the rotation permanently and the bot would never aim at
        // anything again, so faceLocation has to refuse the degenerate case.
        bot.faceLocation(bot.position());

        helper.assertValueEqual(bot.getYRot(), before, "yaw must be unchanged, not NaN");
        helper.assertFalse(Float.isNaN(bot.getXRot()), "pitch must not be NaN");

        registry.reset();
        helper.succeed();
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew runGameTestServer
```

Expected: compile failure, `cannot find symbol: method look(Direction)`.

- [ ] **Step 3: Add the rotation methods to `Bot`**

Insert into `src/main/java/net/nuggetmc/tplus/bot/Bot.java`, after `removeBot()`:

```java
    // ---- rotation and looking ---------------------------------------------

    /** Pass-through. {@code Entity.getBoundingBox()} is public final, so this cannot override. */
    public AABB getBotBoundingBox() {
        return getBoundingBox();
    }

    public void setBotPitch(float pitch) {
        setXRot(pitch);
    }

    /**
     * Turns the bot to face {@code target}.
     *
     * <p>Ported from {@code Bot.faceLocation}. Upstream passed {@code keepYaw = false}, so
     * both yaw and pitch are recomputed and the head-rotation packet goes out.
     */
    public void faceLocation(Vec3 target) {
        look(MotionVec.of(target.subtract(position())), false);
    }

    /**
     * Turns the bot to face a block face.
     *
     * <p>Ported from {@code Bot.look(BlockFace)}. UP and DOWN keep the current yaw — a bot
     * looking at the block under its feet must not spin to face north to do it.
     *
     * <p>26.2 renamed the unit vector: {@code Direction.getUnitVec3()} replaces 1.21's
     * {@code step()}.
     */
    public void look(Direction face) {
        look(MotionVec.of(face.getUnitVec3()), face == Direction.DOWN || face == Direction.UP);
    }

    private void look(MotionVec dir, boolean keepYaw) {
        // Not upstream's: upstream could not reach this state because Bukkit's Vector threw
        // on a zero normalize and the exception unwound. MotionVec reproduces the NaN
        // faithfully instead (see spec §2.5), which means a zero direction would write NaN
        // into yRot and the bot would never aim again. Refuse it here, at the one place
        // every caller funnels through.
        if (dir.lengthSquared() == 0 || BotMath.isNotFinite(dir)) {
            return;
        }

        float yaw;
        float pitch;

        if (keepYaw) {
            yaw = getYRot();
            pitch = BotMath.fetchPitch(dir);
        } else {
            float[] vals = BotMath.fetchYawPitch(dir);
            yaw = vals[0];
            pitch = vals[1];

            setYHeadRot(yaw);
            BotFactory.broadcast(this, new ClientboundRotateHeadPacket(this, (byte) (yaw * 256 / 360f)));
        }

        // Entity.setRot is protected in 26.2 — reachable here because Bot is a subclass, but
        // not from Navigation. That is why turning is a Bot method and not a helper.
        setRot(yaw, pitch);
    }
```

Add imports: `net.minecraft.core.Direction`, `net.minecraft.network.protocol.game.ClientboundRotateHeadPacket`,
`net.minecraft.world.phys.AABB`, `net.nuggetmc.tplus.motion.BotMath`.

- [ ] **Step 4: Run the GameTests and confirm they pass**

```bash
./gradlew runGameTestServer
```

Expected: `All 22 required tests passed :)` — 17 from Plan A plus 5 new — and exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/Bot.java src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java
git commit -m "feat: add bot rotation and looking"
```

---
## Task 3: Pose, animation and held item

Six methods, one of which is deliberately empty. `registerPose` is **commented out in its entirety**
upstream — read it and see:

```bash
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java | sed -n '860,890p'
```

Both statements in its body are `//`-prefixed, so it has always been a no-op, and the pose a client
draws has always come from whatever the entity tracker happened to sync. Port it as an empty method
with that fact recorded, rather than "fixing" it: a bot that suddenly changes hitbox height when it
crouches is a different bot from the one this port is reproducing.

There is a second consequence worth writing down. `setShiftKeyDown` and `setSwimming` set shared
flags in `entityData`, and vanilla recomputes an entity's dimensions from its **pose**, in
`LivingEntity.tick`. `Bot.doTick` calls only `detectEquipmentUpdates()` and `baseTick()`, so that
code never runs for a bot and the hitbox never changes. Upstream had exactly the same gap. The
agent only ever reads these flags back through `isBotBlocking`/`isShiftKeyDown`, so nothing depends
on the visual.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java`

- [ ] **Step 1: Write the failing GameTests**

Append to `BotActionTests`:

```java
    @GameTest
    @EmptyTemplate(floor = true)
    static void sneak_then_stand_toggles_the_shift_flag(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.sneak();
        helper.assertTrue(bot.isShiftKeyDown(), "sneak must set the shift flag");

        bot.stand();
        helper.assertFalse(bot.isShiftKeyDown(), "stand must clear the shift flag");
        helper.assertFalse(bot.isSwimming(), "stand must clear the swim flag too");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void swim_sets_the_swim_flag(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.swim();

        helper.assertTrue(bot.isSwimming(), "swim must set the swim flag");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void set_item_puts_the_stack_in_the_main_hand(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.setItem(new ItemStack(Items.DIAMOND_PICKAXE));

        // PlayerEquipment routes MAINHAND to inventory.setSelectedItem, so both views agree.
        helper.assertTrue(bot.getMainHandItem().is(Items.DIAMOND_PICKAXE), "main hand item");
        helper.assertTrue(bot.getInventory().getSelectedItem().is(Items.DIAMOND_PICKAXE),
                "the inventory's selected slot is the main hand for a player");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void a_null_item_restores_the_default_item(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        // Upstream's contract: setItem(null) means "go back to the default", and the agent
        // relies on it — resetHand and move both call setItem(null) every few ticks to drop
        // whatever tool the mining code put there.
        bot.setDefaultItem(new ItemStack(Items.WOODEN_AXE));
        bot.setItem(new ItemStack(Items.DIAMOND_PICKAXE));

        bot.setItem(null);

        helper.assertTrue(bot.getMainHandItem().is(Items.WOODEN_AXE),
                "setItem(null) must restore the default item, not empty the hand");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void set_shield_equips_and_unequips_the_offhand(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.setShield(true);
        helper.assertTrue(bot.getOffhandItem().is(Items.SHIELD), "offhand after enabling the shield");

        bot.setShield(false);
        helper.assertTrue(bot.getOffhandItem().isEmpty(), "offhand after disabling the shield");

        registry.reset();
        helper.succeed();
    }
```

Add imports to the test file: `net.minecraft.world.item.ItemStack`, `net.minecraft.world.item.Items`.

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew runGameTestServer
```

Expected: compile failure, `cannot find symbol: method sneak()`.

- [ ] **Step 3: Add the field and the methods to `Bot`**

Add the field next to the existing velocity fields in `src/main/java/net/nuggetmc/tplus/bot/Bot.java`:

```java
    /**
     * What {@code setItem(null)} falls back to. Upstream initialised this to an AIR stack;
     * the vanilla equivalent is {@code ItemStack.EMPTY}, which {@code ItemUtils} scores as
     * bare fists — the same 0.25 damage the Paper build gave an AIR stack.
     */
    private ItemStack defaultItem = ItemStack.EMPTY;

    private boolean shield;
    private boolean blocking;
    private boolean blockUse;
```

Then the methods, after the rotation block from Task 2:

```java
    // ---- pose, animation and equipment ------------------------------------

    /** Swings the main hand. Vanilla broadcasts the animation packet for us. */
    public void punch() {
        swing(InteractionHand.MAIN_HAND);
    }

    public void swim() {
        setSwimming(true);
        registerPose(Pose.SWIMMING);
    }

    public void sneak() {
        // Bukkit's setSneaking is vanilla's setShiftKeyDown; both set shared flag 1.
        setShiftKeyDown(true);
        registerPose(Pose.CROUCHING);
    }

    public void stand() {
        setShiftKeyDown(false);
        setSwimming(false);
        registerPose(Pose.STANDING);
    }

    /**
     * Deliberately empty, and deliberately kept.
     *
     * <p>Upstream's body is two commented-out statements — it has never done anything. Making
     * it work would start syncing poses and, through {@code refreshDimensions}, start changing
     * the bot's hitbox when it crouches. That is a behaviour change, not a bug fix, so it is
     * out of scope for a faithful port. The parameter is retained so the call sites read the
     * same as upstream's.
     */
    private void registerPose(Pose pose) {
    }

    public void setDefaultItem(ItemStack item) {
        this.defaultItem = item;
    }

    /** Main hand. A null {@code item} means "restore the default item". */
    public void setItem(ItemStack item) {
        setItem(item, EquipmentSlot.MAINHAND);
    }

    public void setItemOffhand(ItemStack item) {
        setItem(item, EquipmentSlot.OFFHAND);
    }

    /**
     * Ported from {@code Bot.setItem(ItemStack, EquipmentSlot)}.
     *
     * <p>{@code setItemSlot} is the vanilla route for both slots: {@code PlayerEquipment.set}
     * sends MAINHAND to {@code inventory.setSelectedItem} and everything else to the backing
     * equipment map, so the inventory and the equipment view cannot disagree. The Paper build
     * wrote the Bukkit inventory and then sent the packet; only the first half changes.
     *
     * <p>The equipment packet is still sent by hand, for the same reason the spawn packet is:
     * bots are not announced to clients the way tracked players are.
     */
    public void setItem(ItemStack item, EquipmentSlot slot) {
        ItemStack stack = item == null ? defaultItem : item;

        setItemSlot(slot, stack);

        BotFactory.broadcast(this, new ClientboundSetEquipmentPacket(
                getId(), List.of(Pair.of(slot, stack))));
    }

    /** Puts a shield in the offhand and allows {@link #block(int, int)} to fire. */
    public void setShield(boolean enabled) {
        this.shield = enabled;

        setItemOffhand(enabled ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
    }
```

Add imports: `com.mojang.datafixers.util.Pair`,
`net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket`,
`net.minecraft.world.InteractionHand`, `net.minecraft.world.entity.EquipmentSlot`,
`net.minecraft.world.entity.Pose`, `net.minecraft.world.item.ItemStack`,
`net.minecraft.world.item.Items`, `java.util.List`.

`ClientboundSetEquipmentPacket` takes `List<Pair<EquipmentSlot, ItemStack>>`; upstream built an
`ArrayList` around a singleton, which `List.of` replaces.

- [ ] **Step 4: Run the GameTests and confirm they pass**

```bash
./gradlew runGameTestServer
```

Expected: `All 27 required tests passed :)` and exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/Bot.java src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java
git commit -m "feat: add bot pose, animation and equipment"
```

---

## Task 4: Velocity, combat and state accessors

The agent reads and writes the bot's velocity constantly, and **it mutates what it is given**.
`checkUp` does `npc.getVelocity().add(vector)` and `move` does `vel.add(bot.getVelocity())` — the
first of those mutates the returned object in place. Upstream survived it because `getVelocity()`
returned `velocity.clone()`. This is the single most dangerous line in the whole port: return the
live vector and the agent silently corrupts the bot's physics on the first tick.

Note that Plan A's `getBotVelocity()` returns the live object on purpose — `BotPhysics.step` mutates
it, which is the whole design. The two accessors therefore differ, and the difference is load-bearing.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java`

Read the originals:

```bash
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java | sed -n '228,266p;396,436p;478,520p'
```

- [ ] **Step 1: Write the failing GameTests**

Append to `BotActionTests`:

```java
    @GameTest
    @EmptyTemplate(floor = true)
    static void get_velocity_returns_a_copy_not_the_live_vector(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setVelocity(new MotionVec(0.1, 0.2, 0.3));

        // The agent does exactly this — checkUp's `npc.getVelocity().add(vector)` mutates
        // whatever it is handed. If that is the live vector, the bot's physics is corrupted
        // from the first tick and nothing downstream reports why.
        bot.getVelocity().add(new MotionVec(99, 99, 99));

        helper.assertValueEqual(bot.getBotVelocity().getX(), 0.1, "x must be untouched");
        helper.assertValueEqual(bot.getBotVelocity().getY(), 0.2, "y must be untouched");
        helper.assertValueEqual(bot.getBotVelocity().getZ(), 0.3, "z must be untouched");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void set_velocity_writes_through_to_the_live_vector(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        MotionVec live = bot.getBotVelocity();

        bot.setVelocity(new MotionVec(1, 2, 3));

        // The velocity field is final (BotPhysics holds and mutates it), so setVelocity
        // copies component-wise rather than rebinding. Same observable result, and the
        // reference BotPhysics captured stays valid.
        helper.assertValueEqual(live.getY(), 2.0, "the same object must see the new value");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void walk_caps_the_combined_speed(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setVelocity(new MotionVec(0.3, 0, 0.3));

        bot.walk(new MotionVec(0.3, 0, 0.3));

        // 0.6,0,0.6 has length 0.848; upstream normalised and scaled back to exactly 0.4.
        helper.assertTrue(Math.abs(bot.getVelocity().length() - 0.4) < 1.0E-6,
                "walk must clamp to 0.4, got " + bot.getVelocity().length());

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void walk_below_the_cap_is_a_plain_sum(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setVelocity(new MotionVec(0.1, 0, 0));

        bot.walk(new MotionVec(0.1, 0, 0));

        helper.assertValueEqual(bot.getVelocity().getX(), 0.2, "under the cap, walk just adds");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void is_falling_is_the_minus_zero_point_eight_threshold(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        bot.setVelocity(new MotionVec(0, -0.79, 0));
        helper.assertFalse(bot.isFalling(), "-0.79 is not falling");

        bot.setVelocity(new MotionVec(0, -0.81, 0));
        helper.assertTrue(bot.isFalling(), "-0.81 is falling");

        // The same constant gates fall damage in fallDamageCheck and the MLG attempt in
        // BlockScan.tryPreMLG. Three behaviours hang off this number.
        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void tick_delay_is_a_modulus_of_alive_ticks(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        // aliveTicks is 0 on spawn, and 0 % n == 0, so every delay fires on the first tick.
        // The agent's "every 3 ticks" attack and "every 20 ticks" centring both rely on it.
        helper.assertTrue(bot.tickDelay(3), "delay 3 must fire at tick 0");
        helper.assertTrue(bot.tickDelay(20), "delay 20 must fire at tick 0");

        bot.tick();
        helper.assertFalse(bot.tickDelay(3), "delay 3 must not fire at tick 1");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void the_spawn_offset_is_inside_a_three_block_circle(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        // Every bot gets a fixed random offset at construction so a crowd of bots chasing
        // one player spreads out instead of stacking. Upstream: MathUtils.circleOffset(3).
        MotionVec offset = bot.getOffset();

        helper.assertTrue(offset.length() <= 3.0 + 1.0E-9,
                "offset must lie within radius 3, got " + offset.length());
        helper.assertValueEqual(offset.getY(), 0.0, "the offset is horizontal");

        registry.reset();
        helper.succeed();
    }
```

Add the import `net.nuggetmc.tplus.motion.MotionVec` to the test file.

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew runGameTestServer
```

Expected: compile failure, `cannot find symbol: method getVelocity()`.

- [ ] **Step 3: Add the offset field**

In `Bot`'s constructor, alongside the existing initialisation:

```java
        this.offset = BotMath.circleOffset(3);
```

and the field:

```java
    /**
     * A fixed random point in a radius-3 horizontal circle, chosen once per bot.
     *
     * <p>{@code LegacyAgent} adds it to the target's position when {@code offsets} is on, so a
     * group of bots converges on a ring around the target instead of a single point.
     */
    private final MotionVec offset;
```

- [ ] **Step 4: Add the velocity, combat and accessor methods**

```java
    // ---- velocity ---------------------------------------------------------

    /**
     * The bot's velocity, as a **copy**.
     *
     * <p>This is not the same thing as {@link #getBotVelocity()}, and the difference matters.
     * {@code getBotVelocity} hands out the live vector because {@code BotPhysics.step} mutates
     * it in place; that is the physics path. Agent code mutates whatever it is given —
     * {@code Navigation.checkUp} does {@code getVelocity().add(v)} — so this path must copy or
     * the agent silently rewrites the bot's motion. Upstream returned {@code velocity.clone()}
     * here for exactly this reason.
     */
    public MotionVec getVelocity() {
        return getBotVelocity().copy();
    }

    /**
     * Replaces the velocity.
     *
     * <p>Upstream rebound the field. Ours is final, because {@code BotPhysics} captures it, so
     * this copies component-wise instead. Observably identical, and it keeps that reference
     * valid.
     */
    public void setVelocity(MotionVec vec) {
        MotionVec live = getBotVelocity();
        live.setX(vec.getX()).setY(vec.getY()).setZ(vec.getZ());
    }

    /**
     * Adds {@code vel}, then clamps the total to 0.4.
     *
     * <p>Ported from {@code Bot.walk}. The clamp is a normalize-and-scale, so a vector already
     * under the cap is unchanged.
     */
    public void walk(MotionVec vel) {
        double max = 0.4;

        MotionVec sum = getVelocity().add(vel);
        if (sum.length() > max) {
            sum.normalize().multiply(max);
        }

        setVelocity(sum);
    }

    // ---- combat -----------------------------------------------------------

    /**
     * Faces the target, swings, and applies 1.8 damage for whatever is in hand.
     *
     * <p>Ported from {@code Bot.attack}. The damage comes from {@link ItemUtils}, not from the
     * item's real attack-damage attribute — see that class for why.
     */
    public void attack(LivingEntity target) {
        faceLocation(target.position());
        punch();

        double damage = ItemUtils.getLegacyAttackDamage(defaultItem);

        target.hurtServer((ServerLevel) level(), damageSources().playerAttack(this), (float) damage);
    }

    /**
     * Raises the shield for {@code blockLength} ticks, then locks it out for {@code cooldown}.
     *
     * <p>Ported from {@code Bot.block}. Does nothing unless {@link #setShield(boolean)} put a
     * shield in the offhand, and nothing while a previous block is still on cooldown.
     *
     * <p>In v1 this has no caller: its only upstream caller is the neural-network branch of
     * {@code tickBot} (correction 4). It is ported anyway because {@code hurtServer} consults
     * {@code blocking} in Task 8, and a shield that can never be raised would make that
     * branch untestable.
     */
    public void block(int blockLength, int cooldown) {
        if (!shield || blockUse) {
            return;
        }

        startBlocking();

        if (registry != null) {
            registry.scheduler().runLater(blockLength, () -> stopBlocking(cooldown));
        }
    }

    private void startBlocking() {
        this.blocking = true;
        this.blockUse = true;

        startUsingItem(InteractionHand.OFF_HAND);
        BotFactory.broadcast(this, new ClientboundSetEntityDataPacket(getId(), getEntityData().packDirty()));
    }

    private void stopBlocking(int cooldown) {
        this.blocking = false;

        stopUsingItem();

        if (registry != null) {
            registry.scheduler().runLater(cooldown, () -> this.blockUse = false);
        }

        BotFactory.broadcast(this, new ClientboundSetEntityDataPacket(getId(), getEntityData().packDirty()));
    }

    /**
     * Whether the bot is blocking.
     *
     * <p>Upstream delegated to vanilla {@code isBlocking()} rather than reading its own
     * {@code blocking} flag, and the two can disagree: vanilla also requires the item to have
     * been in use past its warmup. Delegating is what the Paper build did, so it is what this
     * does; the private flag stays because the damage path in Task 8 reads it directly, the
     * same way upstream's {@code hurt} did.
     */
    public boolean isBotBlocking() {
        return isBlocking();
    }

    // ---- state ------------------------------------------------------------

    public MotionVec getOffset() {
        return offset;
    }

    public boolean isFalling() {
        return getBotVelocity().getY() < -0.8;
    }

    /** True every {@code i}th tick of the bot's life. {@code aliveTicks} starts at 0. */
    public boolean tickDelay(int i) {
        return getAliveTicks() % i == 0;
    }

    public boolean isBotAlive() {
        return isAlive();
    }

    public boolean isBotOnFire() {
        return isOnFire();
    }

    public String getBotName() {
        return getGameProfile().name();
    }

    /**
     * True in the Nether.
     *
     * <p>Upstream's {@code getDimension()} returned Bukkit's {@code World.Environment} and
     * every one of its five call sites compared it to {@code NETHER}, so the predicate is the
     * faithful translation of the accessor.
     */
    public boolean isNether() {
        return level().dimension() == Level.NETHER;
    }

    public UUID getTargetPlayer() {
        return targetPlayer;
    }

    public void setTargetPlayer(UUID target) {
        this.targetPlayer = target;
    }

    public int getKills() {
        return kills;
    }

    public void incrementKills() {
        kills++;
    }
```

Add the two remaining fields next to `defaultItem`:

```java
    private UUID targetPlayer;
    private int kills;
```

Widen `getNoFallTicks()` from package-private to `public` — `BlockScan.tryPreMLG` calls it from
another package.

Add imports: `net.minecraft.world.entity.LivingEntity`, `net.minecraft.world.level.Level`,
`net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket`,
`net.nuggetmc.tplus.util.ItemUtils`, `java.util.UUID`.

**Two things deliberately not ported here.** `addFriction(double)` has no caller — Plan A folded
friction into `BotPhysics.step`, which is where upstream's only call site
(`Bot.updateLocation`) led. `getBotHealth`/`getBotMaxHealth` have no v1 caller either: both were
read by `Debugger` and `BotEnvironmentCommand`, which are deferred.

- [ ] **Step 5: Run the GameTests and confirm they pass**

```bash
./gradlew runGameTestServer
```

Expected: `All 34 required tests passed :)` and exit 0.

If `the_spawn_offset_is_inside_a_three_block_circle` fails on the Y assertion, check
`BotMath.circleOffset` — Plan A ported it and its Y component must be 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/bot/Bot.java src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java
git commit -m "feat: add bot velocity, combat and state accessors"
```

---

## Task 5: Block placing, and commands to drive it all

`attemptBlockPlace` is the last of the action API, and it is the one that needs a rule from Phase 5
that does not exist yet: upstream guards the placement with `LegacyMats.isSolid(block.getType())`.
Rather than forward-declare `BlockRules`, use the vanilla predicate now and tighten it in Task 13 —
the step is written into that task, so it cannot be forgotten.

This task also adds the `/tplus bot` subcommands that drive every method from Tasks 2-4 by hand.
That is what makes Phase 1 a real milestone instead of an untested library: you can stand in front
of a bot and make it look at you, crouch, swing, hold a pickaxe and place a block.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java`

Read the original:

```bash
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java | sed -n '808,830p'
```

- [ ] **Step 1: Write the failing GameTests**

Append to `BotActionTests`:

```java
    @GameTest
    @EmptyTemplate(floor = true)
    static void attempt_block_place_fills_an_empty_space(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        BlockPos target = helper.absolutePos(new BlockPos(3, 1, 1));
        bot.attemptBlockPlace(target, Blocks.COBBLESTONE, false);

        helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(3, 1, 1));

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void attempt_block_place_refuses_to_overwrite_a_solid_block(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        BlockPos relative = new BlockPos(3, 1, 1);
        helper.setBlock(relative, Blocks.OBSIDIAN);

        bot.attemptBlockPlace(helper.absolutePos(relative), Blocks.COBBLESTONE, false);

        // Upstream guarded on LegacyMats.isSolid. Without the guard a bot towering out of
        // lava would happily replace the bedrock it is standing on.
        helper.assertBlockPresent(Blocks.OBSIDIAN, relative);

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void attempt_block_place_down_looks_down_rather_than_at_the_block(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        bot.setYRot(42f);

        bot.attemptBlockPlace(helper.absolutePos(new BlockPos(3, 1, 1)), Blocks.COBBLESTONE, true);

        // down = true means look(DOWN), which keeps yaw. down = false means faceLocation,
        // which does not. The agent picks between them per situation.
        helper.assertValueEqual(bot.getXRot(), 90f, "pitch when placing downward");
        helper.assertValueEqual(bot.getYRot(), 42f, "yaw must survive a downward place");

        registry.reset();
        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void attempt_block_place_holds_cobblestone(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        // Upstream always put COBBLESTONE in hand, even when placing something else — the
        // `type` parameter and the held item are independent, and only one call site ever
        // passes a different type. Ported as-is.
        bot.attemptBlockPlace(helper.absolutePos(new BlockPos(3, 1, 1)), Blocks.COBBLESTONE, false);

        helper.assertTrue(bot.getMainHandItem().is(Items.COBBLESTONE), "held item after placing");

        registry.reset();
        helper.succeed();
    }
```

Add the import `net.minecraft.world.level.block.Blocks` to the test file.

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew runGameTestServer
```

Expected: compile failure, `cannot find symbol: method attemptBlockPlace`.

- [ ] **Step 3: Add `attemptBlockPlace` to `Bot`**

```java
    /**
     * Places {@code type} at {@code pos} if nothing solid is there, with the animation of a
     * player doing it.
     *
     * <p>Ported from {@code Bot.attemptBlockPlace}. Two details are upstream's and look wrong
     * but are not: the bot always puts **cobblestone** in hand regardless of {@code type}, and
     * the sound is always the stone place sound.
     *
     * <p>The solidity guard is upstream's {@code LegacyMats.isSolid}. Until Task 13 builds
     * {@code BlockRules}, this uses the vanilla predicate, which is the same thing for every
     * block upstream's version did not special-case. Task 13 replaces this line.
     */
    public void attemptBlockPlace(BlockPos pos, Block type, boolean down) {
        if (down) {
            look(Direction.DOWN);
        } else {
            faceLocation(Vec3.atCenterOf(pos));
        }

        setItem(new ItemStack(Items.COBBLESTONE));
        punch();

        ServerLevel level = (ServerLevel) level();
        BlockState state = level.getBlockState(pos);

        if (!state.isSolid()) {
            level.setBlockAndUpdate(pos, type.defaultBlockState());
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1f, 1f);
        }
    }
```

Add imports: `net.minecraft.sounds.SoundEvents`, `net.minecraft.sounds.SoundSource`,
`net.minecraft.world.level.block.Block`.

- [ ] **Step 4: Add the action subcommands**

In `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`, inside `register`, after the
existing `list` line:

```java
        // Drives the action API by hand. This is how Phase 1 is verified outside a GameTest:
        // stand in front of a bot and make it do things.
        root.then(Commands.literal("bot")
                .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.literal("punch").executes(ctx -> act(ctx, Bot::punch)))
                        .then(Commands.literal("sneak").executes(ctx -> act(ctx, Bot::sneak)))
                        .then(Commands.literal("stand").executes(ctx -> act(ctx, Bot::stand)))
                        .then(Commands.literal("swim").executes(ctx -> act(ctx, Bot::swim)))
                        .then(Commands.literal("lookdown")
                                .executes(ctx -> act(ctx, bot -> bot.look(Direction.DOWN))))
                        .then(Commands.literal("lookup")
                                .executes(ctx -> act(ctx, bot -> bot.look(Direction.UP))))
                        .then(Commands.literal("faceme")
                                .executes(ctx -> act(ctx, bot -> bot.faceLocation(senderPos(ctx)))))
                        .then(Commands.literal("shield")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> act(ctx, bot ->
                                                bot.setShield(BoolArgumentType.getBool(ctx, "enabled"))))))
                        .then(Commands.literal("hold")
                                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                        .executes(ctx -> act(ctx, bot -> {
                                            try {
                                                // 26.2 dropped the second parameter:
                                                // createItemStack(int), not (int, boolean).
                                                bot.setItem(ItemArgument.getItem(ctx, "item")
                                                        .createItemStack(1));
                                            } catch (CommandSyntaxException e) {
                                                throw new RuntimeException(e);
                                            }
                                        }))))));
```

`ItemArgument.item(...)` needs the registry access that `RegisterCommandsEvent` carries, so
`register` has to take the event rather than just the dispatcher. Change the signature and the
caller:

```java
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        // The rest of the method body is unchanged: the existing `root` builder, every
        // existing `root.then(...)`, and the closing `dispatcher.register(root)`.
    }
```

and in `TerminatorPlus.onRegisterCommands`, `BotCommands.register(event);`.

Then the two helpers, next to `removeOne`:

```java
    /** Looks up a bot by name and applies {@code action} to it. */
    private static int act(CommandContext<CommandSourceStack> ctx, Consumer<Bot> action) {
        String name = StringArgumentType.getString(ctx, "name");
        Bot bot = TerminatorPlus.registry().byName(name);

        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("No bot named '" + name + "'"));
            return 0;
        }

        action.accept(bot);
        return 1;
    }

    /** Where the command came from, for {@code faceme}. Falls back to the world origin. */
    private static Vec3 senderPos(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getPosition();
    }
```

Add imports: `com.mojang.brigadier.arguments.BoolArgumentType`,
`com.mojang.brigadier.exceptions.CommandSyntaxException`,
`net.minecraft.commands.arguments.item.ItemArgument`, `net.minecraft.core.Direction`,
`net.neoforged.neoforge.event.RegisterCommandsEvent`, `java.util.function.Consumer`.

- [ ] **Step 5: Run the GameTests and confirm they pass**

```bash
./gradlew runGameTestServer
```

Expected: `All 38 required tests passed :)` and exit 0.

- [ ] **Step 6: Verify by hand on a real server**

```bash
./gradlew runServer > run-server.log 2>&1
```

Do not pipe this through `tail` or run it detached and hope — a pipe buffers until the stream ends,
so the log stays empty and the server looks hung. Redirect to a file and read the file, or read
`run/logs/latest.log`.

Then, from the server console or over RCON:

```
/tplus create Dummy
/tplus bot Dummy hold minecraft:diamond_pickaxe
/tplus bot Dummy faceme
/tplus bot Dummy punch
/tplus bot Dummy sneak
/tplus bot Dummy shield true
```

Join with a vanilla client and confirm you can see: the pickaxe in its hand, the bot turning to
look at you, the swing animation, and the shield in its offhand. The crouch will **not** be visible
— that is the `registerPose` no-op from Task 3, and it matches upstream.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java
git commit -m "feat: add block placing and operator action commands"
```

---
# Phase 2: Events, the agent base, and the damage path

## Task 6: The five event types

Read all five first; they are small, and correction 2 explains why only one of them extends
anything:

```bash
for f in BotDamageByPlayerEvent BotDeathEvent BotFallDamageEvent BotKilledByPlayerEvent TerminatorLocateTargetEvent; do
  git show "master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/event/$f.java"
done
```

The one design decision: `BotDeathEvent` carried Bukkit's staged drop list, which vanilla has no
equivalent of — items are spawned directly. NeoForge's `LivingDropsEvent` is the equivalent staging
point, and it exposes `Collection<ItemEntity> getDrops()`, so `BotDeathEvent` carries that
collection and `LegacyAgent.onBotDeath` clears it exactly as upstream did. Dropped XP is not
carried: nothing reads it.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/event/BotDamageByPlayerEvent.java`
- Create: `src/main/java/net/nuggetmc/tplus/event/BotFallDamageEvent.java`
- Create: `src/main/java/net/nuggetmc/tplus/event/BotKilledByPlayerEvent.java`
- Create: `src/main/java/net/nuggetmc/tplus/event/BotDeathEvent.java`
- Create: `src/main/java/net/nuggetmc/tplus/event/TerminatorLocateTargetEvent.java`

- [ ] **Step 1: Write the four plain value classes**

`BotDamageByPlayerEvent.java`:

```java
package net.nuggetmc.tplus.event;

import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.bot.Bot;

/**
 * A player is about to damage a bot. Fired from {@code Bot.hurtServer} before the hit lands.
 *
 * <p>Not a bus event: upstream passed this straight to {@code agent.onPlayerDamage(event)} and
 * never went near the Bukkit event bus (plan correction 2). {@code LegacyAgent} uses it to
 * cancel hits that a raised shield should have blocked.
 */
public final class BotDamageByPlayerEvent {

    private final Bot bot;
    private final ServerPlayer player;
    private float damage;
    private boolean cancelled;

    public BotDamageByPlayerEvent(Bot bot, ServerPlayer player, float damage) {
        this.bot = bot;
        this.player = player;
        this.damage = damage;
    }

    public Bot getBot() {
        return bot;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public float getDamage() {
        return damage;
    }

    public void setDamage(float damage) {
        this.damage = damage;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
```

`BotFallDamageEvent.java`:

```java
package net.nuggetmc.tplus.event;

import net.minecraft.core.BlockPos;
import net.nuggetmc.tplus.bot.Bot;

import java.util.List;

/**
 * A bot is about to take fall damage. Fired from {@code Bot.fallDamageCheck}.
 *
 * <p>{@code standingOn} is what the bot is about to land on, and {@code LegacyAgent.onFallDamage}
 * searches it for somewhere to dump a water bucket — an MLG. Cancelling the event is what
 * "the clutch worked" means.
 *
 * <p>Upstream held Bukkit {@code Block}s and copied the list defensively at the call site. Ours
 * holds positions, because a {@code BlockState} read now could be stale by the time the handler
 * looks at it, and the handler needs the level anyway.
 */
public final class BotFallDamageEvent {

    private final Bot bot;
    private final List<BlockPos> standingOn;
    private boolean cancelled;

    public BotFallDamageEvent(Bot bot, List<BlockPos> standingOn) {
        this.bot = bot;
        this.standingOn = standingOn;
    }

    public Bot getBot() {
        return bot;
    }

    public List<BlockPos> getStandingOn() {
        return standingOn;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
```

`BotKilledByPlayerEvent.java`:

```java
package net.nuggetmc.tplus.event;

import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.bot.Bot;

/**
 * A player just killed a bot.
 *
 * <p>Upstream's comment on this class is worth keeping: "eventually also call this event for
 * deaths from other damage causes within combat time (like hitting the ground too hard)". It
 * does not do that, and neither does this.
 *
 * <p>{@code Agent.onBotKilledByPlayer} uses it to credit the kill — to the killer's own bot, if
 * the killer is driving one.
 */
public final class BotKilledByPlayerEvent {

    private final Bot bot;
    private final ServerPlayer player;

    public BotKilledByPlayerEvent(Bot bot, ServerPlayer player) {
        this.bot = bot;
        this.player = player;
    }

    public Bot getBot() {
        return bot;
    }

    public ServerPlayer getPlayer() {
        return player;
    }
}
```

`BotDeathEvent.java`:

```java
package net.nuggetmc.tplus.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.nuggetmc.tplus.bot.Bot;

import java.util.Collection;

/**
 * A bot died, and its drops have not been spawned yet.
 *
 * <p>Upstream extended Bukkit's {@code EntityDeathEvent} to get at the staged drop list. Vanilla
 * has no staging step, so {@code BotRegistry} bridges NeoForge's {@code LivingDropsEvent} into
 * this and hands over that event's live collection. {@code LegacyAgent.onBotDeath} clears it when
 * drops are disabled, which is the only thing any handler ever did with it.
 *
 * <p>The collection is mutable on purpose. Clearing it is the API.
 */
public final class BotDeathEvent {

    private final Bot bot;
    private final DamageSource source;
    private final Collection<ItemEntity> drops;

    public BotDeathEvent(Bot bot, DamageSource source, Collection<ItemEntity> drops) {
        this.bot = bot;
        this.source = source;
        this.drops = drops;
    }

    public Bot getBot() {
        return bot;
    }

    public DamageSource getSource() {
        return source;
    }

    public Collection<ItemEntity> getDrops() {
        return drops;
    }
}
```

- [ ] **Step 2: Write the one real bus event**

`TerminatorLocateTargetEvent.java`:

```java
package net.nuggetmc.tplus.event;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.nuggetmc.tplus.bot.Bot;
import org.jetbrains.annotations.Nullable;

/**
 * A bot has chosen a target, and anything on the bus may change or veto it.
 *
 * <p>The only one of the five that really was an event upstream: it went through
 * {@code Bukkit.getPluginManager().callEvent}, so third-party plugins could retarget bots. That
 * extension point is preserved here on {@code NeoForge.EVENT_BUS}, which is the nearest thing a
 * server-side mod has to it.
 *
 * <p>Cancelling means "this bot has no target this tick" — {@code Targeting.locateTarget} returns
 * null — which is different from setting the target to null, because a handler that cancels stops
 * later handlers from seeing it.
 *
 * <p>The target may legitimately be null on entry: {@code locateTarget} posts the event even when
 * it found nothing, so a handler can supply a target the goal would never have picked.
 */
public final class TerminatorLocateTargetEvent extends Event implements ICancellableEvent {

    private final Bot bot;
    private @Nullable LivingEntity target;

    public TerminatorLocateTargetEvent(Bot bot, @Nullable LivingEntity target) {
        this.bot = bot;
        this.target = target;
    }

    public Bot getBot() {
        return bot;
    }

    public @Nullable LivingEntity getTarget() {
        return target;
    }

    public void setTarget(@Nullable LivingEntity target) {
        this.target = target;
    }
}
```

Note: upstream's accessor was `getTerminator()`. Renamed to `getBot()` to match every other event
here and the concrete type the v1 agent works against (spec §4.4, "Internal bot interface").

`ICancellableEvent` supplies `isCanceled()`/`setCanceled(boolean)` as interface defaults — do not
declare a `cancelled` field, and note the American spelling, which differs from the four plain
classes above.

- [ ] **Step 3: Build**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`. No tests yet — these are value classes, and Task 8 is what exercises
them. Do not write tests that only assert a getter returns what the constructor was given.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/event
git commit -m "feat: add the five bot lifecycle events"
```

---

## Task 7: `Agent` and `AgentState`

Two classes and a rewiring. `Agent` loses Bukkit's scheduler and plugin handle; `AgentState` is the
bag of twelve mutable collections that spec §4.3 says to move wholesale rather than distribute.

Read both originals:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/Agent.java
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '36,62p'
```

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/Agent.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/AgentState.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java` — add `agent()`
- Test: `src/test/java/net/nuggetmc/tplus/agent/AgentStateTest.java`

- [ ] **Step 1: Write the failing test for `AgentState`**

`src/test/java/net/nuggetmc/tplus/agent/AgentStateTest.java`:

```java
package net.nuggetmc.tplus.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pure. Only the crack-list key needs testing — the other eleven collections are plain
 * fields and a test that asserts a HashMap behaves like a HashMap is noise.
 */
class AgentStateTest {

    private static ResourceKey<Level> dim(String path) {
        return ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("test", path));
    }

    @Test
    void aCrackKeyIsEqualForTheSamePositionInTheSameLevel() {
        assertEquals(new AgentState.BlockRef(Level.OVERWORLD, new BlockPos(1, 2, 3)),
                new AgentState.BlockRef(Level.OVERWORLD, new BlockPos(1, 2, 3)));
    }

    @Test
    void aCrackKeyDistinguishesLevels() {
        // Upstream keyed crackList on a Bukkit Block, whose equals() compares the world as
        // well as the coordinates. Keying on BlockPos alone would make two bots mining the
        // same coordinates in the Overworld and the Nether share one crack progress counter
        // and cancel each other's animation.
        assertNotEquals(new AgentState.BlockRef(Level.OVERWORLD, new BlockPos(1, 2, 3)),
                new AgentState.BlockRef(Level.NETHER, new BlockPos(1, 2, 3)));
    }

    @Test
    void aCrackKeyDistinguishesPositions() {
        assertNotEquals(new AgentState.BlockRef(dim("a"), new BlockPos(1, 2, 3)),
                new AgentState.BlockRef(dim("a"), new BlockPos(1, 2, 4)));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.agent.AgentStateTest'
```

Expected: compile failure, `cannot find symbol: class AgentState`.

- [ ] **Step 3: Write `AgentState`**

```java
package net.nuggetmc.tplus.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.bot.Bot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every piece of mutable state {@code LegacyAgent} shares with its collaborators.
 *
 * <p>Upstream held all twelve of these as fields on the 1,600-line class, read and written from
 * every concern in it. Spec §4.3: they move here **as a group**, injected into each collaborator,
 * because deciding per-class ownership means guessing at sharing semantics and guessing wrong
 * changes behaviour silently. Narrowing ownership is a separate, deliberate change.
 *
 * <p>Two key-space notes. Upstream keyed some of these on {@code Terminator} and others on the
 * bot's Bukkit {@code LivingEntity} — {@code noFace} on the former, {@code noJump} on the latter.
 * Those are one-to-one, so both become {@code Bot} here and the two key spaces merge with no
 * observable difference. {@code Bot} is safe as a key for the reason Plan A recorded on
 * {@code BotRegistry.bots}: {@code Entity} equality is by entity id, ids come from a monotonic
 * counter, and they never change while an entity lives.
 *
 * <p>The exception is {@code crackList}, which upstream keyed on a Bukkit {@code Block} —
 * world **and** position. {@link BlockRef} preserves that; see the test for what a bare
 * {@code BlockPos} would break.
 */
public final class AgentState {

    /** A block identified by level and position, matching Bukkit {@code Block} equality. */
    public record BlockRef(ResourceKey<Level> level, BlockPos pos) {
    }

    /** Bots that must not be turned to face their target this tick. */
    public final Set<Bot> noFace = new HashSet<>();

    /** Bots that must not jump — set while mining the block underfoot. */
    public final Set<Bot> noJump = new HashSet<>();

    /** Bots moving at half speed, set during a clutch. */
    public final Set<Bot> slow = new HashSet<>();

    /**
     * Bots with a mining swing animation running, mapped to the scheduler task id.
     *
     * <p>Upstream mapped to the {@code BukkitRunnable} itself and cancelled it directly. Our
     * {@code TickScheduler} hands out ids, so the id is what is stored; cancellation is
     * {@code scheduler.cancel(id)}.
     */
    public final Map<Bot, Integer> miningAnim = new HashMap<>();

    /** Boats spawned to carry a bot over lava. */
    public final Set<Boat> boats = new HashSet<>();

    /** Each bot's position as of the last centring check, 20 ticks ago. */
    public final Map<Bot, Vec3> btList = new HashMap<>();

    /** Whether each bot has stayed in the same block column since the last check. */
    public final Map<Bot, Boolean> btCheck = new HashMap<>();

    /** Bots currently towering, mapped to where they started. */
    public final Map<Bot, Vec3> towerList = new HashMap<>();

    /** Bots that have just used a boat and must not spawn another yet. */
    public final Set<Bot> boatCooldown = new HashSet<>();

    /**
     * Blocks being mined, mapped to the break-animation id sent to clients.
     *
     * <p>The value is upstream's {@code random.nextInt(2000)} — an arbitrary id so that two
     * bots cracking two blocks do not overwrite each other's animation.
     */
    public final Map<BlockRef, Short> crackList = new HashMap<>();

    /** Mining task id to progress stage, 0 through 9. */
    public final Map<Integer, Byte> mining = new HashMap<>();

    /** Bots that took fall damage recently and must not be shoved downward again. */
    public final Set<Bot> fallDamageCooldown = new HashSet<>();

    /** Forgets everything about {@code bot}. Called when a bot is removed. */
    public void forget(Bot bot) {
        noFace.remove(bot);
        noJump.remove(bot);
        slow.remove(bot);
        miningAnim.remove(bot);
        btList.remove(bot);
        btCheck.remove(bot);
        towerList.remove(bot);
        boatCooldown.remove(bot);
        fallDamageCooldown.remove(bot);
    }
}
```

`forget` is **not** upstream. Upstream leaked an entry in nine collections per dead bot, forever —
the maps are keyed by entity and nothing ever removed them. For a plugin that spawns a hundred bots
and kills them repeatedly that is a slow leak of dead `ServerPlayer` references, each holding an
inventory and an advancement tracker. Task 12 calls it from `BotRegistry.remove`. Flagged here as a
deliberate addition; it cannot change behaviour, because every lookup is keyed by a live bot.

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.agent.AgentStateTest'
```

Expected: 3 tests, 0 failures.

- [ ] **Step 5: Write `Agent`**

```java
package net.nuggetmc.tplus.agent;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.event.BotDeathEvent;
import net.nuggetmc.tplus.event.BotFallDamageEvent;
import net.nuggetmc.tplus.event.BotKilledByPlayerEvent;
import net.nuggetmc.tplus.util.TickScheduler;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * What drives a bot's decisions. Ported from {@code api/agent/Agent}.
 *
 * <p>Three things changed in the translation, all of them because Bukkit's scheduler is gone.
 *
 * <p><b>There is no repeating task.</b> Upstream's {@code setEnabled(true)} called
 * {@code scheduleSyncRepeatingTask(plugin, this::tick, 0, 1)} and {@code setEnabled(false)}
 * cancelled it. {@code BotRegistry.tick()} already runs every server tick and already isolates
 * each bot, so enabling is a flag it consults rather than a task to schedule. Same cadence, and
 * the per-bot isolation from spec §7 survives, which a self-scheduling agent would have bypassed.
 *
 * <p><b>Tasks are ids, not runnables.</b> {@code TickScheduler} hands out ints, so
 * {@code taskList} holds ints and {@code stopAllTasks} cancels them.
 *
 * <p><b>{@code onBotKilledByPlayer} runs on the server thread.</b> Upstream wrapped a registry
 * lookup and an integer increment in {@code runTaskAsynchronously}, which bought nothing and
 * raced with the registry. Nothing observable changes.
 */
public abstract class Agent {

    protected final @Nullable BotRegistry registry;
    protected final Set<Integer> taskList = new HashSet<>();
    protected final Random random = new Random();

    protected boolean enabled;
    protected boolean drops;

    protected Agent(@Nullable BotRegistry registry) {
        this.registry = registry;
        setEnabled(true);
    }

    /** A do-nothing agent, so callers never have to null-check {@link Bot#agent()}. */
    public static Agent noop(@Nullable BotRegistry registry) {
        return new Agent(registry) {
            @Override
            protected void tick() {
            }
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean b) {
        enabled = b;

        if (!b) {
            stopAllTasks();
        }
    }

    public void setDrops(boolean enabled) {
        this.drops = enabled;
    }

    public boolean isDrops() {
        return drops;
    }

    /**
     * Schedules {@code action} and remembers the id so {@link #stopAllTasks()} can cancel it.
     *
     * <p>Every delayed call in the agent goes through here. Upstream had 27 bare
     * {@code runTaskLater} calls and only added some of them to {@code taskList}, which is why
     * disabling the agent used to leave swing animations running.
     */
    public int later(long delayTicks, Runnable action) {
        if (registry == null) {
            return -1;
        }

        int id = registry.scheduler().runLater(delayTicks, action);
        taskList.add(id);
        return id;
    }

    protected @Nullable TickScheduler scheduler() {
        return registry == null ? null : registry.scheduler();
    }

    /**
     * Cancels a task this agent scheduled and forgets its id.
     *
     * <p>Public rather than protected: {@code Mining} and {@code Navigation} both cancel tasks
     * they started, and they are in another package. A one-method interface between classes
     * that ship together would be ceremony.
     */
    public void cancel(int id) {
        TickScheduler scheduler = scheduler();

        if (scheduler != null) {
            scheduler.cancel(id);
        }

        taskList.remove(id);
    }

    /** The shared randomness source. {@code Mining} uses it for crack-animation ids. */
    public Random random() {
        return random;
    }

    public void stopAllTasks() {
        TickScheduler scheduler = scheduler();

        if (scheduler != null) {
            taskList.forEach(scheduler::cancel);
        }

        taskList.clear();
    }

    /**
     * Called once per server tick before {@link #tickBot(Bot)} runs for any bot.
     *
     * <p>Upstream's {@code tick()} computed {@code botsInPlayerList} and then looped over every
     * bot itself. The loop lives in {@code BotRegistry} so that one bot throwing cannot stop the
     * others, so the per-tick setup splits out here.
     */
    protected abstract void tick();

    /** Called once per live bot per tick, from {@code BotRegistry.tickBot}. */
    public void tickBot(Bot bot) {
    }

    public void onFallDamage(BotFallDamageEvent event) {
    }

    public void onPlayerDamage(BotDamageByPlayerEvent event) {
    }

    public void onBotDeath(BotDeathEvent event) {
    }

    /**
     * Credits a kill. If the killer is itself driving a bot, that bot's tally goes up.
     *
     * <p>Upstream looked the killer up by entity id, which finds a bot when the killer *is* a
     * bot — that is how bot-versus-bot scores work.
     */
    public void onBotKilledByPlayer(BotKilledByPlayerEvent event) {
        if (registry == null) {
            return;
        }

        Bot killer = registry.byEntityId(event.getPlayer().getId());

        if (killer != null) {
            killer.incrementKills();
        }
    }
}
```

- [ ] **Step 6: Wire the registry**

In `BotRegistry`, add the agent and the two lookups the agent needs:

```java
    /**
     * Starts as a no-op so nothing has to null-check. Task 12 replaces it with LegacyAgent, and
     * a test can swap in a stub.
     */
    private Agent agent = Agent.noop(this);

    public Agent agent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent.stopAllTasks();
        this.agent = agent;
    }

    /** Ported from {@code BotManagerImpl.getBot(int)}. */
    public Bot byEntityId(int entityId) {
        for (Bot bot : bots) {
            if (bot.getId() == entityId) {
                return bot;
            }
        }
        return null;
    }
```

Replace the `tickBot` stub and the `tick` body:

```java
    /** Called once per server tick. */
    public void tick() {
        scheduler.tick();

        if (agent.isEnabled()) {
            // Per-tick setup, outside the per-bot loop: if this throws there is no single bot
            // to blame, so it is deliberately not inside the isolation below.
            agent.tick();
        }

        for (Bot bot : List.copyOf(bots)) {
            if (!bot.isAlive() && bot.isRemoved()) {
                remove(bot);
                continue;
            }

            try {
                tickBot(bot);
            } catch (Throwable t) {
                noteTickFailure(bot, t);
            }
        }
    }

    private void tickBot(Bot bot) {
        if (agent.isEnabled()) {
            agent.tickBot(bot);
        }
    }
```

and make `remove` forget the bot's agent state:

```java
    public void remove(Bot bot) {
        bots.remove(bot);
        failures.remove(bot);
        state.forget(bot);
    }
```

with the state owned by the registry, so both the registry and the agent see the same instance:

```java
    private final AgentState state = new AgentState();

    public AgentState state() {
        return state;
    }
```

Also make `reset()` stop the agent's tasks — `stopAllTasks` is what cancels in-flight swing
animations and shield timers:

```java
    public void reset() {
        agent.stopAllTasks();

        for (Bot bot : List.copyOf(bots)) {
            safeRemove(bot);
        }
        bots.clear();
        failures.clear();
        scheduler.cancelAll();
    }
```

- [ ] **Step 7: Add `Bot.agent()`**

```java
    /**
     * The agent that owns this bot, never null.
     *
     * <p>A bot always has a registry in practice — {@code BotFactory.spawn} registers before the
     * bot enters the level — but the damage path must not NPE if one somehow does not, so an
     * orphan gets a shared no-op.
     */
    public Agent agent() {
        return registry != null ? registry.agent() : ORPHAN_AGENT;
    }

    private static final Agent ORPHAN_AGENT = Agent.noop(null);
```

- [ ] **Step 8: Build, then run everything**

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: `BUILD SUCCESSFUL` and 57 unit tests passing. The GameTests that exercise this land in
Task 8, which is the next task.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus src/test/java/net/nuggetmc/tplus/agent
git commit -m "feat: add the Agent base, AgentState and registry wiring"
```

---

## Task 8: The damage path

Upstream overrode `hurt(DamageSource, float)`. In 26.2 the server-side entry point is
`hurtServer(ServerLevel, DamageSource, float)` — `ServerPlayer` overrides it, so `Bot` overrides
that. Plan A already **calls** `hurtServer` from `fallDamageCheck` but never overrode it, so all
four of upstream's damage behaviours are still missing: the player-damage event, the shield-block
sound, knockback, and the kill credit.

Read the original:

```bash
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java | sed -n '694,760p'
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java`
- Modify: `src/main/java/net/nuggetmc/tplus/TerminatorPlus.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotCombatTests.java` (new)

- [ ] **Step 1: Write the failing GameTests**

`src/gametest/java/net/nuggetmc/tplus/gametest/BotCombatTests.java`:

```java
package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.event.BotDamageByPlayerEvent;

/**
 * In-world tests for the damage path.
 *
 * <p>Bots inherit the level's default gamemode, and the GameTest level is CREATIVE, which makes
 * every damage assertion vacuously true. Plan A was bitten by this; every test here sets
 * SURVIVAL explicitly.
 *
 * <p>A freshly constructed ServerPlayer carries {@code invulnerableTime = 60}, so a hit before
 * the bot has ticked is silently ignored. Tick past it first.
 */
@ForEachTest(groups = BotCombatTests.GROUP)
public final class BotCombatTests {

    public static final String GROUP = "bot.combat";

    private BotCombatTests() {
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(relative));

        Bot bot = BotFactory.spawn(registry, level, pos, 0f, 0f,
                BotGameProfiles.create("CombatBot", null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    /** Clears the 60-tick spawn invulnerability. */
    private static void warmUp(Bot bot) {
        for (int i = 0; i < 70; i++) {
            bot.tick();
        }
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(floor = true)
    static void a_player_hit_fires_the_damage_event_and_lands(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);

        var player = helper.makeMockServerPlayer(GameType.SURVIVAL);
        float before = bot.getHealth();

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 4f);

        helper.assertTrue(bot.getHealth() < before, "an unblocked player hit must reduce health");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(floor = true)
    static void a_cancelled_damage_event_stops_the_hit(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);

        // Stand in for the agent: the real handler cancels when a shield is raised and the
        // attacker is in front. Here the interest is only that cancelling is honoured, so
        // the registry's agent is replaced with one that always cancels.
        registry.setAgent(new AlwaysBlockingAgent(registry));

        var player = helper.makeMockServerPlayer(GameType.SURVIVAL);
        float before = bot.getHealth();

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 4f);

        helper.assertValueEqual(bot.getHealth(), before, "a cancelled event must not damage the bot");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(floor = true)
    static void a_modified_damage_value_is_used(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);

        registry.setAgent(new HalvingAgent(registry));

        var player = helper.makeMockServerPlayer(GameType.SURVIVAL);
        float before = bot.getHealth();

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 4f);

        // 4 halved to 2. Upstream read event.getDamage() back after dispatch, so a handler
        // can soften a hit as well as veto it; nothing in v1 uses it, and it is one line.
        float taken = before - bot.getHealth();
        helper.assertTrue(taken > 1.5f && taken < 2.5f, "expected about 2 damage, took " + taken);

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(floor = true)
    static void a_surviving_hit_knocks_the_bot_back(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 3));
        warmUp(bot);

        var player = helper.makeMockServerPlayer(GameType.SURVIVAL);
        player.snapTo(helper.absoluteVec(new Vec3(1, 1, 3)), 0f, 0f);

        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(player), 1f);

        // Upstream's kb() replaces the velocity outright rather than adding to it, and the
        // copy-paste bug in it means both horizontal components come from the X difference.
        // The only safe assertion is therefore "it moved", not a direction.
        helper.assertTrue(bot.getVelocity().length() > 0.0,
                "a surviving bot must be knocked back");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(floor = true)
    static void a_non_player_hit_skips_the_event_and_the_knockback(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));
        warmUp(bot);
        registry.setAgent(new AlwaysBlockingAgent(registry));

        float before = bot.getHealth();

        // No attacker entity at all: upstream's `attacker instanceof ServerPlayer` is false,
        // so the event never fires and the always-cancelling agent cannot save the bot.
        bot.hurtServer(helper.getLevel(), helper.getLevel().damageSources().fall(), 3f);

        helper.assertTrue(bot.getHealth() < before,
                "non-player damage must bypass BotDamageByPlayerEvent entirely");

        registry.reset();
        helper.succeed();
    }
}
```

Add the two stub agents at the bottom of the same file, after the class:

```java
/** Cancels every player hit, standing in for a bot with a raised shield. */
final class AlwaysBlockingAgent extends net.nuggetmc.tplus.agent.Agent {

    AlwaysBlockingAgent(BotRegistry registry) {
        super(registry);
    }

    @Override
    protected void tick() {
    }

    @Override
    public void onPlayerDamage(BotDamageByPlayerEvent event) {
        event.setCancelled(true);
    }
}

/** Halves every player hit, to prove setDamage is read back. */
final class HalvingAgent extends net.nuggetmc.tplus.agent.Agent {

    HalvingAgent(BotRegistry registry) {
        super(registry);
    }

    @Override
    protected void tick() {
    }

    @Override
    public void onPlayerDamage(BotDamageByPlayerEvent event) {
        event.setDamage(event.getDamage() / 2f);
    }
}
```

These reference `Agent` and `BotRegistry.setAgent` from Task 7, so they compile and run as soon as
this task's code lands.

- [ ] **Step 2: Override `hurtServer` in `Bot`**

```java
    /**
     * Ported from {@code Bot.hurt(DamageSource, float)}. 26.2 renamed the server-side entry
     * point to {@code hurtServer} and threads the level through it.
     *
     * <p>Four behaviours live here, in upstream's order: the player-damage event (which can
     * veto or soften the hit), the shield-block sound when the hit is refused, knockback for a
     * hit the bot survives, and the kill credit for one it does not.
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        Entity attacker = source.getEntity();

        // Deliberately NOT `&& !(attacker instanceof Bot)`. Bot extends ServerPlayer, so
        // upstream's `attacker instanceof ServerPlayer` was true for a bot attacker too —
        // and that is load-bearing: it is how bot-versus-bot kills get counted, through
        // Agent.onBotKilledByPlayer looking the killer up in the registry. Excluding bots
        // here would silently break every NEAREST_BOT goal's kill tally.
        boolean fromPlayer = attacker instanceof ServerPlayer;

        float damage = amount;
        ServerPlayer killer = null;

        if (fromPlayer) {
            killer = (ServerPlayer) attacker;

            BotDamageByPlayerEvent event = new BotDamageByPlayerEvent(this, killer, amount);
            agent().onPlayerDamage(event);

            if (event.isCancelled()) {
                return false;
            }

            damage = event.getDamage();
        }

        boolean damaged = super.hurtServer(level, source, damage);

        // Upstream keyed this off its own `blocking` flag rather than vanilla isBlocking(),
        // and the two can disagree — see isBotBlocking. Kept as upstream had it.
        if (!damaged && blocking) {
            level.playSound(null, blockPosition(), SoundEvents.SHIELD_BLOCK.value(),
                    SoundSource.PLAYERS, 1f, 1f);
        }

        if (damaged && attacker != null) {
            if (fromPlayer && !isAlive()) {
                agent().onBotKilledByPlayer(new BotKilledByPlayerEvent(this, killer));
            } else {
                kb(position(), attacker.position(), attacker);
            }
        }

        return damaged;
    }

    /**
     * Knockback, ported from {@code Bot.kb}.
     *
     * <p>Two upstream oddities, both preserved. It **replaces** the velocity rather than adding
     * to it, so a hit cancels whatever the bot was doing. And it reads the Knockback enchantment
     * off the attacker's main hand, which is the only place in the whole plugin that any
     * enchantment is consulted.
     */
    private void kb(Vec3 self, Vec3 attackerPos, Entity attacker) {
        MotionVec vel = MotionVec.of(self.subtract(attackerPos)).setY(0).normalize().multiply(0.3);

        if (isBotOnGround()) {
            vel.multiply(0.8).setY(0.4);
        }

        if (attacker instanceof LivingEntity living) {
            int level = knockbackLevel(living);

            if (level == 1) {
                vel.multiply(1.05).setY(0.4);
            } else if (level > 1) {
                vel.multiply(1.9).setY(0.4);
            }
        }

        setVelocity(vel);
    }

    /**
     * Knockback enchantment level on the attacker's main hand, or 0.
     *
     * <p>26.2 keeps enchantments in a data component and looks them up through a registry
     * holder, so the Bukkit {@code ItemMeta.hasEnchant} test becomes a registry lookup plus an
     * {@code EnchantmentHelper} query.
     */
    private int knockbackLevel(LivingEntity attacker) {
        return attacker.level().registryAccess()
                .lookup(Registries.ENCHANTMENT)
                .flatMap(registry -> registry.get(Enchantments.KNOCKBACK))
                .map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, attacker.getMainHandItem()))
                .orElse(0);
    }
```

Verify the enchantment lookup against the patched sources before trusting the shape above — this
is the one call in the task with no upstream analogue to check against:

```bash
grep -n "getItemEnchantmentLevel" /tmp/mcsrc/net/minecraft/world/item/enchantment/EnchantmentHelper.java
grep -n "KNOCKBACK" /tmp/mcsrc/net/minecraft/world/item/enchantment/Enchantments.java
```

If the signature differs, keep the behaviour (0 / 1 / >1 tiers) and adjust the call. If the
registry is unreachable from this context at all, return 0 and note it: a bot that ignores Knockback
is a smaller deviation than a bot that crashes on being hit with an enchanted sword.

Add imports: `net.minecraft.core.registries.Registries`,
`net.minecraft.world.damagesource.DamageSource`, `net.minecraft.world.entity.Entity`,
`net.minecraft.world.item.enchantment.EnchantmentHelper`,
`net.minecraft.world.item.enchantment.Enchantments`,
`net.nuggetmc.tplus.event.BotDamageByPlayerEvent`,
`net.nuggetmc.tplus.event.BotKilledByPlayerEvent`.

`agent()` is a convenience on `Bot` that returns the owning registry's agent, or a do-nothing agent
when there is no registry. Task 7 added it.

- [ ] **Step 3: Fire the fall-damage event**

Replace the last line of `Bot.fallDamageCheck`:

```java
        BotFallDamageEvent event = new BotFallDamageEvent(this, List.copyOf(getStandingOn()));
        agent().onFallDamage(event);

        if (!event.isCancelled()) {
            hurtServer((ServerLevel) level(), damageSources().fall(), (float) Math.pow(3.6, -oldY));
        }
```

The copy is upstream's (`new ArrayList<>(getStandingOn())`) and matters: the handler places blocks,
which makes `checkGround` recompute `standingOn` underneath it.

- [ ] **Step 4: Bridge the death and mob-target hooks**

Upstream listened to two Bukkit events in `BotManagerImpl`: `EntityDeathEvent` to fire
`BotDeathEvent`, and `EntityTargetLivingEntityEvent` to stop mobs targeting bots when
`mobTarget` is off. Both get NeoForge equivalents. Add to `TerminatorPlus`:

```java
    /**
     * Bridges NeoForge's drop event into {@code BotDeathEvent}.
     *
     * <p>{@code LivingDropsEvent} is the closest thing vanilla has to Bukkit's staged drop list,
     * and it is the only point at which clearing the drops still suppresses them.
     */
    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Bot bot) {
            REGISTRY.agent().onBotDeath(
                    new BotDeathEvent(bot, event.getSource(), event.getDrops()));
        }
    }

    /**
     * Stops mobs picking bots as a target unless it has been turned on.
     *
     * <p>Upstream's {@code onMobTarget}. NeoForge's {@code LivingChangeTargetEvent} is fired for
     * exactly this and cancelling it leaves the previous target in place, which is what Bukkit's
     * cancellation did too.
     */
    @SubscribeEvent
    public void onChangeTarget(LivingChangeTargetEvent event) {
        if (REGISTRY.isMobTarget()) {
            return;
        }

        if (event.getNewAboutToBeSetTarget() instanceof Bot) {
            event.setCanceled(true);
        }
    }
```

Add `mobTarget` to `BotRegistry` with a getter and setter, defaulting to **false** — upstream's
`BotManagerImpl` field default:

```java
    private boolean mobTarget;

    public boolean isMobTarget() {
        return mobTarget;
    }

    public void setMobTarget(boolean mobTarget) {
        this.mobTarget = mobTarget;
    }
```

Also port `onJoin`: upstream re-sent every bot's render packets to a joining player, with a 10-tick
delay before the last one, because a client that was not connected when the bot spawned has never
seen it.

```java
    /**
     * Renders every live bot to a player who has just joined.
     *
     * <p>Upstream's {@code onJoin}. The delay on the final packet is upstream's too: a client
     * that has only just finished logging in drops entity data sent in the same tick.
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof Bot) {
            return;
        }

        for (Bot bot : REGISTRY.bots()) {
            BotFactory.renderTo(bot, player, true);
        }
    }
```

Add `BotFactory.renderTo(Bot, ServerPlayer, boolean login)`, factoring the existing `render(Bot)`
so the packets are built once and can go to one connection or all of them:

```java
    /** Sends the packets one client needs in order to draw {@code bot}. */
    public static void renderTo(Bot bot, ServerPlayer target, boolean login) {
        Packet<?>[] packets = renderPackets(bot);

        target.connection.send(packets[0]);
        target.connection.send(packets[1]);

        if (login) {
            // Upstream delayed the last packet by 10 ticks on login. Without it a client that
            // has only just joined discards the entity data and the bot renders as a default
            // skin with no equipment.
            BotRegistry registry = bot.getRegistry();
            if (registry != null) {
                registry.scheduler().runLater(10, () -> target.connection.send(packets[2]));
            } else {
                target.connection.send(packets[2]);
            }
        } else {
            target.connection.send(packets[2]);
        }
    }
```

Keep `render(Bot)` as the broadcast form and have both share `renderPackets(Bot)`, which is the
current body of `render` turned into a `Packet<?>[]` — add-entity, entity-data, rotate-head, in
that order.

Add imports to `TerminatorPlus`: `net.minecraft.server.level.ServerPlayer`,
`net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent`,
`net.neoforged.neoforge.event.entity.living.LivingDropsEvent`,
`net.neoforged.neoforge.event.entity.player.PlayerEvent`, `net.nuggetmc.tplus.bot.Bot`,
`net.nuggetmc.tplus.bot.BotFactory`, `net.nuggetmc.tplus.event.BotDeathEvent`.

- [ ] **Step 5: Build and run the combat tests**

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: `BUILD SUCCESSFUL` and the five `bot.combat` tests passing. Task 7 put `Agent`,
`BotRegistry.setAgent` and `Bot.agent()` in place, so nothing here is left dangling — if this task
does not compile, something in Task 7 was skipped.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus src/gametest/java/net/nuggetmc/tplus/gametest/BotCombatTests.java
git commit -m "feat: wire the bot damage path and lifecycle event bridges"
```

---

# Phase 3: The PlayerList path

## Task 9: Bots in the real `PlayerList`

Spec risk 1. Plan A proved the cheap route impossible — `PlayerList.getPlayers()` returns
`Collections.unmodifiableList(players)`, annotated "Neo: Return an unmodifiable view, we don't want
people removing things without us knowing" — and deferred the rest here.

**Read `placeNewPlayer` before writing anything**, because the obvious plan is the wrong one:

```bash
sed -n '145,230p' /tmp/mcsrc/net/minecraft/server/players/PlayerList.java
```

It does far more than insert into a list. It builds a fresh `ServerGamePacketListenerImpl` and
rebinds `player.connection`, calls `connection.setupInboundProtocol`, suspends and resumes channel
flushing, sends the login/difficulty/abilities/held-slot packets, syncs datapacks, recipes,
the recipe book, the scoreboard and active effects, teleports the player, fires
`OnDatapackSyncEvent` and `PlayerLoggedInEvent`, and **broadcasts "X joined the game" to everyone
on the server**.

Upstream's `addToPlayerList` did none of that. It inserted into the list, broadcast
`createPlayerInitializing`, and called `level.addNewPlayer`. So `placeNewPlayer` is not a faithful
implementation of this feature — it is a louder one, and the join message alone makes spawning a
hundred bots unusable.

The faithful route needs one private field, which is what access transformers are for — the port
already has one for `detectEquipmentUpdates`. Two of the three pieces need no AT at all:
`getPlayersByUUID()` returns the live map, and `ServerLevel.addNewPlayer` is public.

**Files:**
- Modify: `src/main/resources/META-INF/accesstransformer.cfg`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/BotFactory.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java` — `removeBot` has a latent crash
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotGameTests.java`

- [ ] **Step 1: Fix the latent crash in `removeBot` first**

`Bot.removeBot` currently contains:

```java
        if (isInPlayerList()) {
            level().getServer().getPlayerList().getPlayers().remove(this);
            setInPlayerList(false);
        }
```

`getPlayers()` is the unmodifiable view, so this throws `UnsupportedOperationException`. It has
never fired because `BotFactory.spawn` throws before any bot can reach `inPlayerList = true` — this
task makes it reachable. Note it, and come back to it in Step 4 once the AT exists.

- [ ] **Step 2: Write the failing GameTests**

Plan A's `BotGameTests` already has a test asserting the playerlist path throws. **Replace it**
rather than adding alongside, so the suite does not simultaneously claim both behaviours. Find it
by name:

```bash
grep -n "playerlist\|PlayerList\|UnsupportedOperation" src/gametest/java/net/nuggetmc/tplus/gametest/BotGameTests.java
```

Then:

```java
    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("bot_joins_the_player_list")
    static void botJoinsTheRealPlayerList(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();

        int before = server.getPlayerList().getPlayerCount();

        Bot bot = BotFactory.spawn(registry, level,
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))), 0f, 0f,
                BotGameProfiles.create("ListBot", null), true);

        // The whole point of the feature: the server counts the bot as an online player.
        helper.assertValueEqual(server.getPlayerList().getPlayerCount(), before + 1,
                "player count after a playerlist spawn");
        helper.assertTrue(server.getPlayerList().getPlayers().contains(bot),
                "the bot must be in the player list");
        helper.assertTrue(bot.isInPlayerList(), "the bot must know it is in the list");

        bot.removeBot();

        helper.assertValueEqual(server.getPlayerList().getPlayerCount(), before,
                "removeBot must take the bot back out of the list");
        helper.assertFalse(server.getPlayerList().getPlayers().contains(bot),
                "the bot must be gone from the player list");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder("playerlist_spawn_is_silent")
    static void aPlayerListSpawnDoesNotAnnounceAJoin(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        ServerLevel level = helper.getLevel();

        // Upstream's insert was silent. placeNewPlayer would broadcast "ListBot joined the
        // game" to every player, which is why this task does not use it. There is no clean
        // hook to assert the absence of a broadcast, so this asserts the thing that proves
        // placeNewPlayer was not used: the bot's connection is still the fake one the factory
        // gave it, not a fresh ServerGamePacketListenerImpl built around a real Connection.
        Bot bot = BotFactory.spawn(registry, level,
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))), 0f, 0f,
                BotGameProfiles.create("QuietBot", null), true);

        helper.assertTrue(bot.connection != null, "the bot keeps a packet listener");
        helper.assertTrue(bot.connection.getConnection() instanceof BotConnection,
                "the bot's connection must still be BotConnection");

        registry.reset();
        helper.succeed();
    }
```

Confirm the accessor name before running — `ServerGamePacketListenerImpl` exposes its `Connection`
and the getter has moved before:

```bash
grep -nE "public Connection get|protected final Connection" /tmp/mcsrc/net/minecraft/server/network/ServerGamePacketListenerImpl.java /tmp/mcsrc/net/minecraft/server/network/ServerCommonPacketListenerImpl.java
```

- [ ] **Step 3: Add the access transformer entry**

Append to `src/main/resources/META-INF/accesstransformer.cfg`:

```
# Bots can optionally join the real PlayerList, which upstream did with a plain
# getPlayers().add(bot). NeoForge deliberately narrowed getPlayers() to an unmodifiable view, so
# the backing list is the only way to reproduce that without placeNewPlayer's side effects (a
# join broadcast, PlayerLoggedInEvent, a datapack and recipe sync, a teleport). See Plan B task 9.
# playersByUUID needs no entry: getPlayersByUUID() already returns the live map.
public net.minecraft.server.players.PlayerList players
```

- [ ] **Step 4: Implement the spawn and removal paths**

In `BotFactory.spawn`, replace the `throw` with:

```java
        if (addToPlayerList) {
            PlayerList list = server.getPlayerList();

            // Upstream did this, and only this: insert, announce, add to the level. See the
            // access transformer for why the field rather than getPlayers().
            list.players.add(bot);
            bot.setInPlayerList(true);

            broadcast(bot, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot)));
            level.addNewPlayer(bot);
        } else {
            level.addFreshEntity(bot);
            broadcast(bot, new ClientboundPlayerInfoUpdatePacket(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, bot));
        }
```

**Do not** also put the bot in `playersByUUID`. Upstream did not, so `server.getPlayerList()
.getPlayer(uuid)` does not find a playerlist bot, and that asymmetry is upstream's. It is recorded
here because it looks exactly like an oversight to fix: doing so would change which entities
vanilla systems can resolve by UUID, and nothing in this port needs it.

In `Bot.removeBot`, fix the crash from Step 1:

```java
        if (isInPlayerList()) {
            // Not getPlayers(): that is an unmodifiable view and removing through it throws.
            // Mirrors the insert in BotFactory.spawn.
            level().getServer().getPlayerList().players.remove(this);
            setInPlayerList(false);
        }
```

`PlayerList.remove(ServerPlayer)` is public and would do a tidier job — it also clears advancement
triggers, removes the entity and broadcasts the info-remove packet. It is **not** used here because
it additionally calls `save(player)`, writing a playerdata file per bot, and fires
`PlayerLoggedOut`. Upstream fired neither. If disk churn ever stops mattering more than tidiness,
that swap is a one-liner.

- [ ] **Step 5: Expose the option on the command**

`BotCommands.create` already takes a `playerList` flag that nothing sets. Wire it up, and delete
the comment saying the path is unsupported:

```java
        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> create(ctx, 1, false))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_BOTS_PER_COMMAND))
                                .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "count"), false))
                                .then(Commands.literal("playerlist")
                                        .executes(ctx -> create(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"), true))))));
```

- [ ] **Step 6: Run the GameTests**

```bash
./gradlew runGameTestServer
```

Expected: both new tests pass, and the old "playerlist throws" test is gone.

**If a vanilla code path throws instead** — most likely something iterating `players` and touching
a field a real join would have initialised — do not start adding ATs to chase it. Record exactly
which call failed, revert to the `UnsupportedOperationException` with that evidence in the message,
and move on: everything after this task is independent of it, and a documented "not supported,
because X throws" is a better outcome than a half-working join path. Spec risk 1 is then closed
either way.

- [ ] **Step 7: Verify by hand**

```bash
./gradlew runServer > run-server.log 2>&1
```

```
/tplus create Listed 2 playerlist
/list
```

`/list` should count the bots, and they should appear in the client's tab list with skins. Then
`/tplus removeall` and confirm `/list` returns to just you — that is the removal path, which is the
half most likely to be broken.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus src/main/resources/META-INF/accesstransformer.cfg src/gametest/java/net/nuggetmc/tplus/gametest/BotGameTests.java
git commit -m "feat: support bots in the real PlayerList; fix removeBot crash"
```

---
# Phase 4: The bot hunts

The milestone. By the end of Task 12 a bot spawned with `/tplus create` picks a target, turns to
face it, closes the distance by jumping toward it, swims when it is in water, and hits it when it is
in range. Everything the bot does to *terrain* — mining, towering, clutching — is Phases 5 to 7.

That split works because `tickBot`'s terrain branches all funnel into `preBreak`, and each of them
returns a boolean meaning "handled, stop here". Task 12 lands the flow with those branches absent
rather than stubbed, and each later task inserts its branch in upstream's position. The order of the
checks is load-bearing and is written out in Task 12 so later tasks have somewhere exact to insert.

## Task 10: `TargetGoal` and `Targeting`

`locateTarget` is 133 lines of switch and `validateCloserEntity` is the comparison every branch
funnels through. Both move verbatim.

One scope note. Three goals (`NEAREST_HOSTILE`, `NEAREST_RAIDER`, `NEAREST_MOB`) and all of
`CUSTOM_LIST` branch on `customListMode` and `CUSTOM_MOB_LIST`, whose type `CustomListMode` spec
§4.4 defers. It is 28 lines and four of eleven goals read it, so **port it** — omitting it would
mean rewriting those four branches, which is exactly what a faithful translation is trying to avoid.
What stays deferred is its only writer, `BotEnvironmentCommand`, so `CUSTOM_MOB_LIST` is always
empty in v1 and `CUSTOM_LIST` finds nothing. That is identical to upstream's behaviour with an
unconfigured list.

Read the originals:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/EnumTargetGoal.java
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/CustomListMode.java
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '1402,1600p'
```

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/TargetGoal.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/CustomListMode.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/Targeting.java`
- Test: `src/test/java/net/nuggetmc/tplus/agent/legacy/TargetGoalTest.java`
- Test: `src/test/java/net/nuggetmc/tplus/agent/legacy/RegionWeightTest.java`

- [ ] **Step 1: Write the failing pure tests**

`TargetGoalTest.java`:

```java
package net.nuggetmc.tplus.agent.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure: the enum has no game dependency. */
class TargetGoalTest {

    @Test
    void everyGoalIsReachableByItsLowercaseName() {
        // Upstream's VALUES map is hand-maintained and hand-keyed, so a goal can be added to
        // the enum and silently be unreachable from the command. This catches that.
        for (TargetGoal goal : TargetGoal.values()) {
            String key = goal.name().toLowerCase().replace("_", "");
            assertEquals(goal, TargetGoal.from(key), "goal not reachable by name: " + key);
        }
    }

    @Test
    void anUnknownNameIsNullRatherThanADefault() {
        // Callers distinguish "no such goal" from NONE, so from() must not fall back.
        assertNull(TargetGoal.from("nonsense"));
        assertNotNull(TargetGoal.from("none"));
    }

    @Test
    void everyGoalHasADescription() {
        for (TargetGoal goal : TargetGoal.values()) {
            assertNotNull(goal.description(), goal + " has no description");
        }
    }
}
```

`RegionWeightTest.java`:

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure: region weighting is arithmetic on an AABB.
 *
 * <p>This is the part of Targeting worth testing in isolation. It decides which targets a bot
 * will even consider, the rules are non-obvious, and it needs no world.
 */
class RegionWeightTest {

    private static final AABB REGION = new AABB(0, 0, 0, 10, 10, 10);

    @Test
    void noRegionMeansNoPenalty() {
        assertEquals(0.0, Targeting.weightedRegionDist(null, new Vec3(500, 500, 500), 1, 1, 1));
    }

    @Test
    void aPointInsideTheRegionHasNoPenalty() {
        assertEquals(0.0, Targeting.weightedRegionDist(REGION, new Vec3(5, 5, 5), 1, 1, 1));
    }

    @Test
    void allZeroWeightsMakeTheRegionAHardBoundary() {
        // Upstream's special case: with every weight at zero, anything outside the region is
        // Double.MAX_VALUE, which validateCloserEntity treats as "not a candidate at all".
        // This is how /tplus region confines bots rather than just biasing them.
        assertEquals(Double.MAX_VALUE,
                Targeting.weightedRegionDist(REGION, new Vec3(20, 5, 5), 0, 0, 0));
        assertEquals(0.0, Targeting.weightedRegionDist(REGION, new Vec3(5, 5, 5), 0, 0, 0));
    }

    @Test
    void thePenaltyIsSquaredDistanceOutsideTheRegionTimesTheWeight() {
        // A 10-wide region centred on 5: half-width 5, so x = 20 is 10 outside. 10^2 * 2 = 200.
        assertEquals(200.0, Targeting.weightedRegionDist(REGION, new Vec3(20, 5, 5), 2, 0, 0));
    }

    @Test
    void eachAxisIsWeightedIndependently() {
        // Upstream's point: a region can be soft vertically and hard horizontally, so bots
        // stay in an arena but may still chase someone who jumps.
        assertEquals(0.0, Targeting.weightedRegionDist(REGION, new Vec3(5, 30, 5), 1, 0, 1));
        assertEquals(400.0, Targeting.weightedRegionDist(REGION, new Vec3(5, 30, 5), 0, 1, 0));
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.agent.legacy.*'
```

Expected: compile failure, `cannot find symbol: class TargetGoal`.

- [ ] **Step 3: Write `TargetGoal` and `CustomListMode`**

Both are near-verbatim. `TargetGoal` keeps upstream's hand-keyed lookup map, because the command
layer's tab completion and error messages depend on those exact keys:

```java
package net.nuggetmc.tplus.agent.legacy;

import java.util.HashMap;
import java.util.Map;

/** Ported verbatim from {@code EnumTargetGoal}; only the name is shortened. */
public enum TargetGoal {
    NEAREST_VULNERABLE_PLAYER("Locate the nearest real player that is in either Survival or Adventure mode."),
    NEAREST_PLAYER("Locate the nearest real online player, despite the gamemode."),
    NEAREST_HOSTILE("Locate the nearest hostile entity."),
    NEAREST_RAIDER("Locate the nearest raider."),
    NEAREST_MOB("Locate the nearest mob."),
    NEAREST_BOT("Locate the nearest bot."),
    NEAREST_BOT_DIFFER("Locate the nearest bot with a different username."),
    NEAREST_BOT_DIFFER_ALPHA("Locate the nearest bot with a different username after filtering out non-alpha characters."),
    CUSTOM_LIST("Locate only the mob types specified in the custom list of mobs"),
    PLAYER("Target a single player. Defaults to NEAREST_VULNERABLE_PLAYER if no player found."),
    NONE("No target goal.");

    private static final Map<String, TargetGoal> VALUES = new HashMap<>();

    static {
        for (TargetGoal goal : values()) {
            VALUES.put(goal.name().toLowerCase().replace("_", ""), goal);
        }
    }

    private final String description;

    TargetGoal(String description) {
        this.description = description;
    }

    /** @return the goal with this name, or null. Callers distinguish null from {@link #NONE}. */
    public static TargetGoal from(String name) {
        return VALUES.get(name);
    }

    public String description() {
        return description;
    }
}
```

Upstream built `VALUES` with eleven hand-written `put` calls. The static loop produces the same
eleven keys — verify with the first test, which is there precisely to prove the substitution — and
cannot fall out of step when a goal is added.

`CustomListMode` is upstream's enum unchanged; read it and copy it across, dropping only the Bukkit
import if it has one.

- [ ] **Step 4: Write `Targeting`**

The full class. `weightedRegionDist` is deliberately `static` and parameterised rather than reading
fields, so the pure test above can reach it.

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.event.TerminatorLocateTargetEvent;
import net.nuggetmc.tplus.util.PlayerUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks what a bot should chase. Ported from {@code LegacyAgent.locateTarget},
 * {@code validateCloserEntity} and {@code getWeightedRegionDist}, plus the region accessors.
 */
public final class Targeting {

    private static final Pattern NAME_PATTERN = Pattern.compile("[^A-Za-z]+");

    /**
     * Mob types the {@code CUSTOM_LIST} goal and the custom branches of the hostile, raider and
     * mob goals consider.
     *
     * <p>Always empty in v1: its only writer is {@code BotEnvironmentCommand}, deferred by spec
     * §4.4. An unconfigured list finds nothing, which is exactly what upstream did.
     */
    public static final Set<EntityType<?>> CUSTOM_MOB_LIST = new HashSet<>();

    public static CustomListMode customListMode = CustomListMode.CUSTOM;

    private final BotRegistry registry;

    private TargetGoal goal = TargetGoal.NEAREST_VULNERABLE_PLAYER;
    private @Nullable AABB region;
    private double regionWeightX;
    private double regionWeightY;
    private double regionWeightZ;

    /** Bots that are in the real PlayerList, recomputed once per tick. */
    private List<Bot> botsInPlayerList = List.of();

    public Targeting(BotRegistry registry) {
        this.registry = registry;
    }

    /**
     * Per-tick setup, from upstream's {@code tick()}.
     *
     * <p>Bots that joined the PlayerList look like online players to every player-scanning
     * goal, so they are collected once and excluded. Bots outside the list are invisible to
     * {@code getPlayers()} anyway and never needed excluding.
     */
    public void beginTick() {
        botsInPlayerList = registry.bots().stream().filter(Bot::isInPlayerList).toList();
    }

    public TargetGoal getTargetType() {
        return goal;
    }

    public void setTargetType(TargetGoal goal) {
        this.goal = goal;
    }

    public void setRegion(@Nullable AABB region, double weightX, double weightY, double weightZ) {
        this.region = region;
        this.regionWeightX = weightX;
        this.regionWeightY = weightY;
        this.regionWeightZ = weightZ;
    }

    public @Nullable AABB getRegion() {
        return region;
    }

    public double getRegionWeightX() {
        return regionWeightX;
    }

    public double getRegionWeightY() {
        return regionWeightY;
    }

    public double getRegionWeightZ() {
        return regionWeightZ;
    }

    /**
     * Finds {@code bot}'s target for this tick, or null.
     *
     * <p>Verbatim from upstream's switch, including the fall-through shape: every branch scans a
     * candidate set and keeps whichever passes {@link #validateCloserEntity}, and the event is
     * posted once at the end even when nothing was found.
     */
    public @Nullable LivingEntity locateTarget(Bot bot, Vec3 pos) {
        return locateTarget(bot, pos, goal);
    }

    public @Nullable LivingEntity locateTarget(Bot bot, Vec3 pos, TargetGoal g) {
        ServerLevel level = (ServerLevel) bot.level();
        MinecraftServer server = level.getServer();
        LivingEntity result = null;

        switch (g) {
            case NONE:
                return null;

            case NEAREST_PLAYER: {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!botsInPlayerList.contains(player) && validateCloserEntity(bot, player, pos, result)) {
                        result = player;
                    }
                }
                break;
            }

            case NEAREST_VULNERABLE_PLAYER: {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!botsInPlayerList.contains(player)
                            && !PlayerUtils.isInvincible(player.gameMode())
                            && validateCloserEntity(bot, player, pos, result)) {
                        result = player;
                    }
                }
                break;
            }

            case NEAREST_HOSTILE: {
                for (LivingEntity entity : livingEntities(level)) {
                    if ((entity instanceof Monster
                            || (customListMode == CustomListMode.HOSTILE
                                    && CUSTOM_MOB_LIST.contains(entity.getType())))
                            && validateCloserEntity(bot, entity, pos, result)) {
                        result = entity;
                    }
                }
                break;
            }

            case NEAREST_RAIDER: {
                for (LivingEntity entity : livingEntities(level)) {
                    boolean raider = entity instanceof Raider
                            || (entity instanceof Vex vex && vex.getOwner() instanceof Raider);

                    if ((raider || (customListMode == CustomListMode.RAIDER
                                    && CUSTOM_MOB_LIST.contains(entity.getType())))
                            && validateCloserEntity(bot, entity, pos, result)) {
                        result = entity;
                    }
                }
                break;
            }

            case NEAREST_MOB: {
                for (LivingEntity entity : livingEntities(level)) {
                    if ((entity instanceof Mob
                            || (customListMode == CustomListMode.MOB
                                    && CUSTOM_MOB_LIST.contains(entity.getType())))
                            && validateCloserEntity(bot, entity, pos, result)) {
                        result = entity;
                    }
                }
                break;
            }

            case NEAREST_BOT: {
                for (Bot other : registry.bots()) {
                    if (bot != other && validateCloserEntity(bot, other, pos, result)) {
                        result = other;
                    }
                }
                break;
            }

            case NEAREST_BOT_DIFFER: {
                String name = bot.getBotName();

                for (Bot other : registry.bots()) {
                    if (bot != other && !name.equals(other.getBotName())
                            && validateCloserEntity(bot, other, pos, result)) {
                        result = other;
                    }
                }
                break;
            }

            case NEAREST_BOT_DIFFER_ALPHA: {
                String name = NAME_PATTERN.matcher(bot.getBotName()).replaceAll("");

                for (Bot other : registry.bots()) {
                    if (bot != other
                            && !name.equals(NAME_PATTERN.matcher(other.getBotName()).replaceAll(""))
                            && validateCloserEntity(bot, other, pos, result)) {
                        result = other;
                    }
                }
                break;
            }

            case CUSTOM_LIST: {
                for (LivingEntity entity : livingEntities(level)) {
                    if (customListMode == CustomListMode.CUSTOM
                            && CUSTOM_MOB_LIST.contains(entity.getType())
                            && validateCloserEntity(bot, entity, pos, result)) {
                        result = entity;
                    }
                }
                break;
            }

            case PLAYER: {
                if (bot.getTargetPlayer() != null) {
                    ServerPlayer player = server.getPlayerList().getPlayer(bot.getTargetPlayer());

                    // Note the `null` third argument, not `result`: upstream passed null here
                    // where every other branch passes the incumbent. With one candidate the
                    // distance comparison is skipped entirely, so a PLAYER-goal bot chases its
                    // named player regardless of range. Kept.
                    if (player != null && !botsInPlayerList.contains(player)
                            && validateCloserEntity(bot, player, pos, null)) {
                        result = player;
                    }
                }
                break;
            }
        }

        TerminatorLocateTargetEvent event = new TerminatorLocateTargetEvent(bot, result);
        NeoForge.EVENT_BUS.post(event);

        return event.isCanceled() ? null : event.getTarget();
    }

    /**
     * Every living entity in the level.
     *
     * <p>Upstream called {@code world.getLivingEntities()}. Vanilla has no level-wide list, so
     * this is the type-test form. It is the same O(all entities) scan upstream did, once per bot
     * per tick for the mob goals — upstream's own header comment on this file reads "Yes, this
     * code is very unoptimized, I know."
     */
    private static List<? extends LivingEntity> livingEntities(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> true);
    }

    /**
     * Whether {@code entity} is a better target than {@code incumbent}.
     *
     * <p>Verbatim, including the parenthesisation of the distance comparison — upstream wrote
     * {@code (a + regionA) < (b) + regionB}, which is the same arithmetic and is left as-is so
     * a diff against {@code master} stays clean.
     */
    private boolean validateCloserEntity(Bot bot, LivingEntity entity, Vec3 pos,
                                         @Nullable LivingEntity incumbent) {
        double regionDistEntity = weightedRegionDist(region, entity.position(),
                regionWeightX, regionWeightY, regionWeightZ);

        if (regionDistEntity == Double.MAX_VALUE) {
            return false;
        }

        double regionDistResult = incumbent == null ? 0 : weightedRegionDist(region,
                incumbent.position(), regionWeightX, regionWeightY, regionWeightZ);

        return bot.level() == entity.level() && entity.isAlive()
                && (incumbent == null
                        || (pos.distanceToSqr(entity.position()) + regionDistEntity)
                                < pos.distanceToSqr(incumbent.position()) + regionDistResult);
    }

    /**
     * How far outside {@code region} {@code pos} is, weighted per axis and squared.
     *
     * <p>Ported from {@code getWeightedRegionDist}. Two rules, both upstream's: inside the
     * region the penalty is zero, and if **all three** weights are zero the region becomes a
     * hard boundary — anything outside returns {@code Double.MAX_VALUE}, which
     * {@link #validateCloserEntity} reads as "not a candidate".
     *
     * <p>Static and parameterised so it can be unit tested without a level.
     */
    public static double weightedRegionDist(@Nullable AABB region, Vec3 pos,
                                            double weightX, double weightY, double weightZ) {
        if (region == null) {
            return 0;
        }

        Vec3 centre = region.getCenter();

        double diffX = Math.max(0, Math.abs(centre.x - pos.x) - region.getXsize() * 0.5);
        double diffY = Math.max(0, Math.abs(centre.y - pos.y) - region.getYsize() * 0.5);
        double diffZ = Math.max(0, Math.abs(centre.z - pos.z) - region.getZsize() * 0.5);

        if (weightX == 0 && weightY == 0 && weightZ == 0) {
            if (diffX > 0 || diffY > 0 || diffZ > 0) {
                return Double.MAX_VALUE;
            }
        }

        return diffX * diffX * weightX + diffY * diffY * weightY + diffZ * diffZ * weightZ;
    }
}
```

Two details to check before moving on. Upstream's `botsInPlayerList` was a
`List<LivingEntity>` of Bukkit entities and the branches tested `contains(player)`; ours is a
`List<Bot>`, so `contains(player)` on a `ServerPlayer` is a cross-type call that Java permits and
that returns true exactly when the player *is* one of those bots — which is the intent. Confirm
`Bot` inherits `Entity`'s id-based equals, as Plan A recorded, or this silently never matches.

And `player.gameMode()` is the 26.2 accessor that `GameTestHelper.makeMockServerPlayer` overrides;
confirm it against the patched sources rather than reaching for `gameMode.getGameMode()`:

```bash
grep -nE "public GameType gameMode\(\)" /tmp/mcsrc/net/minecraft/server/level/ServerPlayer.java
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.agent.legacy.*'
```

Expected: 9 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy src/test/java/net/nuggetmc/tplus/agent/legacy
git commit -m "feat: add target goals and target selection"
```

---

## Task 11: `BlockRules` (first slice), `LegacyUtils` and `LevelRules`

`Navigation` needs a line of sight and two block predicates. The line of sight is
`LegacyUtils.checkFreeSpace`, a 32-steps-per-block ray march that tests every sample against
`LegacyMats.AIR`; the predicates are `AIR` and `WATER`.

`BlockRules` is therefore created here with **exactly those two sets** and finished in Task 13. That
is deliberate: it is a bag of constants, so adding the other ten later is not a redesign, and the
alternative is either forward-declaring a class this task cannot test or pushing the hunting
milestone three tasks later for two constants. Task 13's first step is to re-read this file.

Read the originals:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyMats.java | sed -n '25,45p;81,88p'
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyUtils.java
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyWorldManager.java
```

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockRules.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyUtils.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/LevelRules.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java` (new)

- [ ] **Step 1: Write the failing GameTests**

Tag membership needs a loaded datapack, so this tier is in-world from the start — spec §6.

`src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java`:

```java
package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.BlockRules;
import net.nuggetmc.tplus.agent.legacy.LegacyUtils;
import net.nuggetmc.tplus.agent.legacy.LevelRules;

@ForEachTest(groups = BlockRuleTests.GROUP)
public final class BlockRuleTests {

    public static final String GROUP = "bot.blockrules";

    private BlockRuleTests() {
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void air_includes_the_things_a_bot_can_walk_through(ExtendedGameTestHelper helper) {
        // Upstream's AIR set is not "is this air" — it is "can a bot's line of sight and its
        // body pass through this". Water, lava, fire, snow, vines and tall grass are all in it.
        helper.assertTrue(BlockRules.isAir(Blocks.AIR.defaultBlockState()), "air");
        helper.assertTrue(BlockRules.isAir(Blocks.WATER.defaultBlockState()), "water");
        helper.assertTrue(BlockRules.isAir(Blocks.LAVA.defaultBlockState()), "lava");
        helper.assertTrue(BlockRules.isAir(Blocks.FIRE.defaultBlockState()), "fire");
        helper.assertTrue(BlockRules.isAir(Blocks.SHORT_GRASS.defaultBlockState()), "short grass");
        helper.assertTrue(BlockRules.isAir(Blocks.KELP.defaultBlockState()), "kelp");

        helper.assertFalse(BlockRules.isAir(Blocks.STONE.defaultBlockState()), "stone");
        helper.assertFalse(BlockRules.isAir(Blocks.OAK_FENCE.defaultBlockState()), "a fence");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void water_is_water_and_the_plants_that_live_in_it(ExtendedGameTestHelper helper) {
        // Upstream's WATER set includes seagrass and kelp, because a bot standing in them is
        // standing in water as far as its swim logic is concerned.
        helper.assertTrue(BlockRules.isWater(Blocks.WATER.defaultBlockState()), "water");
        helper.assertTrue(BlockRules.isWater(Blocks.KELP_PLANT.defaultBlockState()), "kelp plant");
        helper.assertTrue(BlockRules.isWater(Blocks.SEAGRASS.defaultBlockState()), "seagrass");

        helper.assertFalse(BlockRules.isWater(Blocks.LAVA.defaultBlockState()), "lava is not water");
        helper.assertFalse(BlockRules.isWater(Blocks.ICE.defaultBlockState()), "ice is not water");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void free_space_is_blocked_by_a_solid_block(ExtendedGameTestHelper helper) {
        Vec3 a = helper.absoluteVec(new Vec3(1.5, 2.5, 1.5));
        Vec3 b = helper.absoluteVec(new Vec3(5.5, 2.5, 1.5));

        helper.assertTrue(LegacyUtils.checkFreeSpace(helper.getLevel(), a, b),
                "an empty corridor must be free");

        helper.setBlock(new BlockPos(3, 2, 1), Blocks.STONE);

        helper.assertFalse(LegacyUtils.checkFreeSpace(helper.getLevel(), a, b),
                "a stone block in the way must block line of sight");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void free_space_is_not_blocked_by_water(ExtendedGameTestHelper helper) {
        Vec3 a = helper.absoluteVec(new Vec3(1.5, 2.5, 1.5));
        Vec3 b = helper.absoluteVec(new Vec3(5.5, 2.5, 1.5));

        helper.setBlock(new BlockPos(3, 2, 1), Blocks.WATER);

        // Follows from AIR containing water: a bot will attack a target through water.
        helper.assertTrue(LegacyUtils.checkFreeSpace(helper.getLevel(), a, b),
                "water must not block line of sight");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void free_space_between_identical_points_is_free(ExtendedGameTestHelper helper) {
        Vec3 a = helper.absoluteVec(new Vec3(1.5, 2.5, 1.5));

        // The ray march divides by the vector's length. Upstream would produce NaN here and
        // the loop bound would be NaN, so the body never ran and it returned true. Ours must
        // reach the same answer without relying on NaN comparison semantics.
        helper.assertTrue(LegacyUtils.checkFreeSpace(helper.getLevel(), a, a),
                "a zero-length ray must be free, not a crash");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(value = "5x30x5", floor = true)
    static void above_ground_means_25_blocks_of_clear_air(ExtendedGameTestHelper helper) {
        BlockPos open = helper.absolutePos(new BlockPos(1, 1, 1));

        helper.assertTrue(LevelRules.aboveGround(helper.getLevel(), Vec3.atBottomCenterOf(open)),
                "an open column must count as above ground");

        helper.setBlock(new BlockPos(1, 12, 1), Blocks.STONE);

        helper.assertFalse(LevelRules.aboveGround(helper.getLevel(), Vec3.atBottomCenterOf(open)),
                "a block anywhere in the 25 above must count as underground");

        helper.succeed();
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew runGameTestServer
```

Expected: compile failure, `cannot find symbol: class BlockRules`.

- [ ] **Step 3: Write the first slice of `BlockRules`**

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * The block predicates the agent decides with. Replaces {@code LegacyMats} (489 lines of
 * hand-maintained {@code Material} lists).
 *
 * <p><b>Partial.</b> This is the slice {@code Navigation} needs — {@code AIR} and {@code WATER}.
 * Task 13 adds the other ten sets and the solidity predicates; Task 14 adds the placement
 * predicates in {@code BlockPlacement}. Nothing here is provisional, there is just less of it
 * than there will be.
 *
 * <p>Where upstream's list is a closed enumeration it stays an explicit {@code Set<Block>}, and
 * where it is really a category it becomes a {@code BlockTags} lookup (Task 13). Tags are the
 * reason this is worth doing: the {@code Material.CHAIN} to {@code IRON_CHAIN} rename is exactly
 * the breakage a tag would have absorbed.
 */
public final class BlockRules {

    /**
     * Blocks a bot can see and move through.
     *
     * <p>Not "is air" — upstream's set includes water, lava, fire, snow layers, vines and every
     * tall plant, because the question it answers is "can a line of sight or a body pass". Ported
     * as an explicit set because it is a curated list, not a category; the duplicate
     * {@code Material.FIRE} entry upstream had is dropped, since a {@code Set} deduplicates it
     * anyway.
     */
    private static final Set<Block> AIR = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.WATER, Blocks.LAVA,
            Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.SNOW,
            Blocks.VINE,
            Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.SUNFLOWER);

    /**
     * Water, and the plants that only exist in it.
     *
     * <p>Upstream's set. Seagrass and kelp are included because a bot standing in them is, for
     * the purposes of its swim logic, in water.
     */
    private static final Set<Block> WATER = Set.of(
            Blocks.WATER,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT);

    private BlockRules() {
    }

    public static boolean isAir(BlockState state) {
        return AIR.contains(state.getBlock());
    }

    public static boolean isWater(BlockState state) {
        return WATER.contains(state.getBlock());
    }
}
```

The test file needs `net.minecraft.world.item.DyeColor` and
`net.minecraft.world.level.block.WeatheringCopper` for the variant families — the weather enum is
the nested `WeatheringCopper.WeatherState` with values UNAFFECTED, EXPOSED, WEATHERED, OXIDIZED, and
the accessor on both collection types is `pick`, not `get` (correction 5). Cross-check the block names against the patched sources before running —
`SHORT_GRASS` was `GRASS` before 1.20.3 and upstream lists both spellings' worth of entries:

```bash
grep -nE "public static final Block (SHORT_GRASS|TALL_GRASS|KELP_PLANT|SUNFLOWER|SOUL_FIRE) =" /tmp/mcsrc/net/minecraft/world/level/block/Blocks.java
```

- [ ] **Step 4: Write `LegacyUtils`**

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Ported from {@code api/agent/legacyagent/LegacyUtils}. */
public final class LegacyUtils {

    private LegacyUtils() {
    }

    /**
     * Whether the straight line from {@code a} to {@code b} passes only through
     * {@link BlockRules#isAir} blocks.
     *
     * <p>Ported from {@code checkFreeSpace}. This is the bot's line of sight: it gates whether a
     * bot attacks, and whether {@code checkDown} and {@code checkSide} decide the target is
     * reachable without mining.
     *
     * <p>The sampling is upstream's and is not a raycast: 32 samples per block of distance,
     * each one converted to a block position and tested. It therefore misses thin diagonal
     * gaps, and it is left exactly as it was.
     *
     * <p>One deviation. Upstream divided by the vector's length without checking it, so two
     * identical points gave a NaN step and a NaN loop bound; the body never ran and it returned
     * true. That works only because every NaN comparison is false, which is too fragile to
     * rely on deliberately, so the zero case returns true explicitly.
     */
    public static boolean checkFreeSpace(ServerLevel level, Vec3 a, Vec3 b) {
        Vec3 v = b.subtract(a);
        double length = v.length();

        if (length == 0) {
            return true;
        }

        int n = 32;
        double m = 1 / (double) n;

        double j = Math.floor(length * n);
        Vec3 step = v.scale(m / length);

        for (int i = 0; i <= j; i++) {
            BlockPos pos = BlockPos.containing(a.add(step.scale(i)));
            BlockState state = level.getBlockState(pos);

            if (!BlockRules.isAir(state)) {
                return false;
            }
        }

        return true;
    }

    /** The break sound for a block. Upstream: {@code getBlockData().getSoundGroup()}. */
    public static SoundEvent breakBlockSound(BlockState state) {
        return state.getSoundType().getBreakSound();
    }
}
```

- [ ] **Step 5: Write `LevelRules`**

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Ported from {@code LegacyWorldManager}, whose class comment — "This is where the respawning
 * queue will be managed" — describes something that was never written. One method.
 */
public final class LevelRules {

    private LevelRules() {
    }

    /**
     * Whether there is nothing but air for the next 25 blocks straight up.
     *
     * <p>{@code Navigation.checkUp} uses it to decide a distant target is not worth towering
     * toward. Note the test is strict air, not {@link BlockRules#isAir} — upstream compared
     * against {@code Material.AIR} here and against its AIR *set* elsewhere, so a bot under
     * water is not "above ground". Kept.
     */
    public static boolean aboveGround(ServerLevel level, Vec3 pos) {
        BlockPos base = BlockPos.containing(pos);

        for (int y = 1; y < 25; y++) {
            if (!level.getBlockState(base.above(y)).isAir()) {
                return false;
            }
        }

        return true;
    }
}
```

- [ ] **Step 6: Run the GameTests and confirm they pass**

```bash
./gradlew runGameTestServer
```

Expected: `All 51 required tests passed :)` and exit 0.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java
git commit -m "feat: add block rules, line of sight and level rules"
```

---
## Task 12: `Navigation.move`, `swim`, and the `LegacyAgent` flow

The milestone. Read `tickBot` and `move` in full before writing anything — the order of the checks
in `tickBot` is the specification for the whole rest of this plan:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '74,210p'
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '208,300p;380,420p'
```

### The `tickBot` order, written out once

Later tasks each insert one line into this sequence. Anything inserted in the wrong place changes
behaviour, because every terrain check returns "handled, stop here":

| # | Call | Lands in |
|---|---|---|
| 1 | `center(bot)` every 20 ticks | Task 12 |
| 2 | `locateTarget` | Task 12 |
| 3 | `blockScan.tryPreMLG(bot, pos)` | Task 23 |
| 4 | *no target →* `mining.stopMining(bot)`, return | Task 19 |
| 5 | `blockScan.clutch(bot, target)` | Task 23 |
| 6 | `fallDamageCheck(bot)` | Task 12 |
| 7 | `behaviors.miscellaneousChecks(bot, target)` | Task 22 |
| 8 | `attack` every 3 ticks, if line of sight | Task 12 |
| 9 | *grounded branch:* `towerList` reset | Task 22 |
| 10 | `checkAt` | Task 16 |
| 11 | `checkFenceAndGates` | Task 16 |
| 12 | `checkObstacles` | Task 16 |
| 13 | `checkDown` | Task 19 |
| 14 | `checkUp`, only when `withinTargetXZ \|\| sameXZ` | Task 19 |
| 15 | `checkSide`, only when `bothXZ` | Task 21 |
| 16 | `move` on side result 1 or 2 | Task 12 |
| 17 | *water branch:* `swim` | Task 12 |

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/BotBehaviors.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java`
- Modify: `src/main/java/net/nuggetmc/tplus/TerminatorPlus.java`
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java` (new)

- [ ] **Step 1: Write the failing GameTests**

`src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java`:

```java
package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.TargetGoal;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;

/**
 * In-world tests for the agent.
 *
 * <p>These drive the registry's tick directly rather than waiting on the server tick, so a test
 * controls exactly how many agent ticks have happened. That matters: almost everything the agent
 * does is gated on {@code tickDelay}, and a test that just waits is testing the scheduler.
 */
@ForEachTest(groups = AgentTests.GROUP)
public final class AgentTests {

    public static final String GROUP = "bot.agent";

    private AgentTests() {
    }

    private static BotRegistry registryWithAgent() {
        BotRegistry registry = new BotRegistry();
        registry.setAgent(new LegacyAgent(registry));
        return registry;
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        Bot bot = BotFactory.spawn(registry, level,
                Vec3.atBottomCenterOf(helper.absolutePos(relative)), 0f, 0f,
                BotGameProfiles.create("HunterBot", null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    private static void run(BotRegistry registry, Bot bot, int ticks) {
        for (int i = 0; i < ticks; i++) {
            registry.tick();
            bot.tick();
        }
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_bot_closes_the_distance_to_a_survival_player(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 7));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(12.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        double before = bot.position().distanceTo(target.position());
        run(registry, bot, 100);
        double after = bot.position().distanceTo(target.position());

        helper.assertTrue(after < before - 1.0,
                "the bot must get closer; was " + before + ", now " + after);

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_bot_turns_to_face_its_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(12.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 20);

        // The target is due east, which is yaw -90 in Minecraft's convention. Allow slack:
        // the offset displaces the aim point by up to three blocks.
        float yaw = Math.abs(bot.getYRot() % 360f);
        helper.assertTrue(yaw > 45f && yaw < 135f,
                "the bot should be facing roughly east, yaw is " + bot.getYRot());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_bot_damages_a_target_in_range(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(8.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        float before = target.getHealth();
        run(registry, bot, 60);

        helper.assertTrue(target.getHealth() < before,
                "a bot next to its target must hurt it; health went " + before
                        + " -> " + target.getHealth());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_bot_ignores_a_creative_player_on_the_vulnerable_goal(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7));

        var target = helper.makeMockServerPlayer(GameType.CREATIVE);
        target.snapTo(helper.absoluteVec(new Vec3(8.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        float before = target.getHealth();
        Vec3 startPos = bot.position();
        run(registry, bot, 60);

        helper.assertValueEqual(target.getHealth(), before,
                "NEAREST_VULNERABLE_PLAYER must not attack a creative player");
        helper.assertTrue(bot.position().distanceTo(startPos) < 1.0,
                "with no valid target the bot should stay put");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void the_none_goal_stops_the_bot_hunting(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        LegacyAgent agent = (LegacyAgent) registry.agent();
        agent.targeting().setTargetType(TargetGoal.NONE);

        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(8.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        float before = target.getHealth();
        run(registry, bot, 60);

        helper.assertValueEqual(target.getHealth(), before, "the NONE goal must not attack");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_disabled_agent_does_nothing(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        registry.agent().setEnabled(false);

        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(8.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        float before = target.getHealth();
        run(registry, bot, 60);

        helper.assertValueEqual(target.getHealth(), before, "a disabled agent must not attack");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_bot_in_water_swims_toward_its_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();

        // A 3x2x3 pool, so the bot is in water with water below it: that is the `anim` case
        // in swim, the one that sets the swimming pose.
        for (int x = 1; x <= 3; x++) {
            for (int z = 6; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
            }
        }

        Bot bot = spawn(helper, registry, new BlockPos(2, 2, 7));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(12.5, 2, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 40);

        helper.assertTrue(bot.getVelocity().length() > 0.0,
                "a bot in water must be pushed toward its target");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void the_centring_check_notices_a_stuck_bot(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        LegacyAgent agent = (LegacyAgent) registry.agent();
        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7));

        // No target, so nothing moves the bot. center() runs every 20 ticks and compares the
        // block column against the previous sample, so after two samples it must report the
        // bot as stationary. sameXZ is what later unlocks checkUp and checkSide.
        run(registry, bot, 45);

        helper.assertTrue(Boolean.TRUE.equals(registry.state().btCheck.get(bot)),
                "a bot that has not moved must be flagged as same-column");

        registry.reset();
        helper.succeed();
    }
}
```

`center` runs before the no-target return in upstream's order, which is what makes the last test
work with no target present. Check that against the table above if it fails.

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew runGameTestServer
```

Expected: compile failure, `cannot find symbol: class LegacyAgent`.

- [ ] **Step 3: Write `Navigation`**

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.motion.MotionVec;

/**
 * How a bot gets from where it is to where its target is.
 *
 * <p>Ported from {@code LegacyAgent.move} and {@code swim}. {@code checkSide}, {@code checkUp}
 * and {@code checkDown} join them in Tasks 19 and 21 — they belong here by spec §4.3, and they
 * need the mining code that does not exist yet.
 */
public final class Navigation {

    private final AgentState state;
    private final Agent agent;

    public Navigation(AgentState state, Agent agent) {
        this.state = state;
        this.agent = agent;
    }

    /**
     * Hops the bot toward {@code target}.
     *
     * <p>Ported from {@code move}. Bots do not walk: every step is a jump with a horizontal
     * impulse, which is why they look the way they do and why {@code isBotOnGround} gates the
     * whole method.
     *
     * <p>The neural-network branch (upstream lines 243-297) is omitted — see plan correction 4.
     * It rotated the impulse by a learned left/right bias and chose between a jump and a
     * walk-then-jump; all of it is unreachable with the AI deferred.
     */
    public void move(Bot bot, LivingEntity livingTarget, Vec3 pos, Vec3 target) {
        MotionVec vel = MotionVec.of(target.subtract(pos)).normalize();

        if (bot.tickDelay(5)) {
            bot.faceLocation(livingTarget.position());
        }

        // Upstream calls this twice — once here, once implicitly through jump's groundTicks
        // check. The comment on the original reads "calling this a second time later on".
        if (!bot.isBotOnGround()) {
            return;
        }

        bot.stand();
        bot.setItem(null);

        vel.add(bot.getVelocity());

        // A deliberate deviation, and the only one in this method. Upstream wrapped the add
        // above in `try { ... } catch (IllegalArgumentException)` and cleaned the vector in the
        // catch — but Bukkit's Vector.add cannot throw that, so the guard never ran. MotionVec
        // propagates NaN silently (spec §2.5), the NaN reaches Entity.move through jump(), and
        // a poisoned position is not something the per-bot tick isolation can undo. The guard
        // therefore runs unconditionally. Reachable when the target is exactly the bot's own
        // position, which `offsets` normally prevents.
        if (BotMath.isNotFinite(vel)) {
            BotMath.clean(vel);
        }

        if (vel.length() > 1) {
            vel.normalize();
        }

        double distance = pos.distanceTo(target);

        if (distance <= 5) {
            vel.multiply(0.3);
        } else {
            vel.multiply(0.4);
        }

        if (state.slow.contains(bot)) {
            // Note the order: setY(0) then multiply, so Y stays 0 rather than being halved.
            vel.setY(0).multiply(0.5);
        } else {
            vel.setY(0.4);
        }

        vel.setY(vel.getY() - Math.random() * 0.05);

        bot.jump(vel);
    }

    /**
     * Pushes the bot through water toward {@code target}.
     *
     * <p>Ported from {@code swim}. Much gentler than {@link #move}: 0.05 of impulse rather than
     * a jump, because water already carries the bot.
     *
     * @param anim true when there is water below the bot too, in which case it also takes the
     *             swimming pose. With no water below, the impulse is flattened and scaled to 0.7.
     */
    public void swim(Bot bot, Vec3 target, LivingEntity livingTarget, boolean anim) {
        // Upstream opened with setSneaking(false) and nothing else. Calling bot.stand() here
        // would be wrong: stand() also clears the swim flag, so the non-anim branch below would
        // silently stop a swimming bot from swimming. setShiftKeyDown is vanilla's public
        // equivalent of Bukkit's setSneaking and touches only the one flag.
        bot.setShiftKeyDown(false);

        Vec3 at = bot.position();
        MotionVec vector = MotionVec.of(target.subtract(at));

        if (BotMath.floorY(at) < BotMath.floorY(livingTarget.position())) {
            vector.setY(0);
        }

        vector.normalize().multiply(0.05);
        vector.setY(vector.getY() * 1.2);

        cancelMiningAnim(bot);

        if (anim) {
            bot.swim();
        } else {
            vector.setY(0);
            vector.multiply(0.7);
        }

        bot.faceLocation(livingTarget.position());
        bot.addVelocity(vector);
    }

    /** Cancels a running swing animation, if any. Upstream inlined this in five places. */
    void cancelMiningAnim(Bot bot) {
        Integer task = state.miningAnim.remove(bot);

        if (task != null) {
            agent.cancel(task);
        }
    }
}
```

`swim` un-sneaks with `setShiftKeyDown(false)` rather than `stand()`, and the difference matters:
`stand()` also clears the swim flag and sets the standing pose, so in the `anim == false` branch —
water at the bot's waist but not below it — a bot that was already swimming would stop. Upstream
called Bukkit's `setSneaking(false)`, which touches one flag. This was `stand()` in an earlier draft
of the plan.

Add `BotMath.floorY(Vec3)` alongside the other helpers — upstream compared `getBlockY()` values, so
this is `Mth.floor(vec.y)`:

```java
    /** Block-coordinate Y, matching Bukkit's {@code Location.getBlockY}. */
    public static int floorY(Vec3 vec) {
        return Mth.floor(vec.y);
    }
```

- [ ] **Step 4: Write `BotBehaviors` (first slice)**

Only `resetHand`. `miscellaneousChecks` and `onBoat` arrive in Task 22; `resetHand` is here because
`tickBot`'s side-result 1 branch calls it, and without it a bot never lets go of a mining tool.

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.world.entity.LivingEntity;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;

/**
 * Odds and ends of bot behaviour that are neither navigation nor mining.
 *
 * <p><b>Partial.</b> {@code miscellaneousChecks} (fire, lava, magma, boats) and {@code onBoat}
 * land in Task 22. {@code resetHand} is here because {@code tickBot} cannot run without it.
 */
public final class BotBehaviors {

    private final AgentState state;
    private final Navigation navigation;

    public BotBehaviors(AgentState state, Navigation navigation) {
        this.state = state;
        this.navigation = navigation;
    }

    /**
     * Faces the target, stops any swing animation, and empties the hand.
     *
     * <p>Ported from {@code resetHand}. Three upstream details are preserved: the {@code noFace}
     * check exists purely as an optimisation (its comment reads "LESSLAG if there is no if
     * statement here"), the early return for a bot on boat cooldown leaves the boat in its hand,
     * and {@code setItem(null)} means "restore the default item", not "empty".
     */
    public void resetHand(Bot bot, LivingEntity target) {
        if (!state.noFace.contains(bot)) {
            bot.faceLocation(target.position());
        }

        navigation.cancelMiningAnim(bot);

        if (state.boatCooldown.contains(bot)) {
            return;
        }

        bot.setItem(null);
    }
}
```

- [ ] **Step 5: Write `LegacyAgent`**

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.event.BotDeathEvent;
import net.nuggetmc.tplus.event.BotFallDamageEvent;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.util.PlayerUtils;
import org.jetbrains.annotations.Nullable;

/**
 * The bot AI. Ported from {@code api/agent/legacyagent/LegacyAgent}, whose own header comment
 * reads "Yes, this code is very unoptimized, I know."
 *
 * <p>Split per spec §4.3 into this plus {@code Targeting}, {@code Navigation},
 * {@code SurroundingScan}, {@code Mining}, {@code BotBehaviors} and {@code BlockScan}. This class
 * keeps the tick flow, the three event handlers and the small helpers, and holds no state of its
 * own beyond the shared {@link AgentState}.
 *
 * <p>The ordering inside {@link #tickBot(Bot)} is the specification for the rest of the port.
 * Every terrain check returns "handled, stop here", so moving one changes behaviour.
 */
public final class LegacyAgent extends Agent {

    private final AgentState state;
    private final Targeting targeting;
    private final Navigation navigation;
    private final BotBehaviors behaviors;

    /** Whether bots aim at a ring around the target rather than the target itself. */
    public boolean offsets = true;

    public LegacyAgent(BotRegistry registry) {
        super(registry);

        this.state = registry.state();
        this.targeting = new Targeting(registry);
        this.navigation = new Navigation(state, this);
        this.behaviors = new BotBehaviors(state, navigation);
    }

    public Targeting targeting() {
        return targeting;
    }

    @Override
    protected void tick() {
        targeting.beginTick();
    }

    @Override
    public void tickBot(Bot bot) {
        if (!bot.isBotAlive()) {
            return;
        }

        if (bot.tickDelay(20)) {
            center(bot);
        }

        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        LivingEntity livingTarget = targeting.locateTarget(bot, pos);

        // Task 23: blockScan.tryPreMLG(bot, pos);

        if (livingTarget == null) {
            // Task 19: mining.stopMining(bot);
            return;
        }

        // Task 23: blockScan.clutch(bot, livingTarget);

        fallDamageCheck(bot);
        // Task 22: behaviors.miscellaneousChecks(bot, livingTarget);

        Vec3 target = offsets
                ? livingTarget.position().add(bot.getOffset().toVec3())
                : livingTarget.position();

        if (bot.tickDelay(3) && !state.miningAnim.containsKey(bot)) {
            Vec3 botEye = bot.getEyePosition();
            Vec3 targetEye = livingTarget.getEyePosition();
            Vec3 targetPos = livingTarget.position();

            // Two rays, not one: eye-to-eye or eye-to-feet. A bot behind a half-height wall
            // can still reach over it.
            if (LegacyUtils.checkFreeSpace(level, botEye, targetEye)
                    || LegacyUtils.checkFreeSpace(level, botEye, targetPos)) {
                attack(bot, livingTarget, pos);
            }
        }

        boolean waterGround = BlockRules.isWater(stateAt(level, pos.add(0, -0.1, 0)))
                && !BlockRules.isAir(stateAt(level, pos.add(0, -0.6, 0)));

        boolean withinTargetXZ = false;
        boolean sameXZ = Boolean.TRUE.equals(state.btCheck.get(bot));

        // Task 22 adds `|| behaviors.onBoat(bot)` to this condition.
        if (waterGround || bot.isBotOnGround()) {
            byte sideResult = 1;

            // Task 22: towerList reset when the bot has climbed above its target.

            if (Math.abs(BotMath.floorX(pos) - BotMath.floorX(target)) <= 3
                    && Math.abs(BotMath.floorZ(pos) - BotMath.floorZ(target)) <= 3) {
                withinTargetXZ = true;
            }

            // Declared here to match upstream's order, and deliberately not read until Task 19
            // adds checkDown. javac does not warn on an unused local, but a reviewer will ask:
            // it is here so the three later insertions do not have to reorder this block.
            boolean bothXZ = withinTargetXZ || sameXZ;

            // Tasks 16, 19 and 21 insert their checks here, in this order:
            //   checkAt, checkFenceAndGates, checkObstacles, checkDown,
            //   checkUp (only when withinTargetXZ || sameXZ), checkSide (only when bothXZ).
            // Each returns true for "handled", and tickBot returns immediately on true.

            switch (sideResult) {
                case 1:
                    behaviors.resetHand(bot, livingTarget);
                    if (!state.noJump.contains(bot) && !waterGround) {
                        navigation.move(bot, livingTarget, pos, target);
                    }
                    return;

                case 2:
                    if (!waterGround) {
                        navigation.move(bot, livingTarget, pos, target);
                    }
            }
        } else if (BlockRules.isWater(stateAt(level, pos))) {
            navigation.swim(bot, target, livingTarget,
                    BlockRules.isWater(stateAt(level, pos.add(0, -1, 0))));
        }
    }

    /**
     * Records whether the bot has stayed in the same block column since the last sample.
     *
     * <p>Ported from {@code center}, called every 20 ticks. {@code sameXZ} is the signal that a
     * bot is stuck, and it is what unlocks {@code checkUp} and {@code checkSide} — a bot that is
     * going nowhere starts mining.
     */
    private void center(Bot bot) {
        if (!bot.isBotAlive()) {
            return;
        }

        Vec3 prev = state.btList.get(bot);
        Vec3 pos = bot.position();

        if (prev != null) {
            state.btCheck.put(bot, BotMath.floorX(pos) == BotMath.floorX(prev)
                    && BotMath.floorZ(pos) == BotMath.floorZ(prev));
        }

        state.btList.put(bot, pos);
    }

    /**
     * Puts the clutch item in the bot's hand while it is falling.
     *
     * <p>Ported from {@code LegacyAgent.fallDamageCheck} — distinct from {@code Bot}'s method of
     * the same name, which is the one that actually applies damage. This only prepares: it looks
     * down and equips a water bucket, or twisting vines in the Nether.
     */
    private void fallDamageCheck(Bot bot) {
        if (!bot.isFalling()) {
            return;
        }

        bot.look(Direction.DOWN);
        bot.setItem(new ItemStack(bot.isNether() ? Items.TWISTING_VINES : Items.WATER_BUCKET));
    }

    /**
     * Hits the target if it is hittable.
     *
     * <p>Ported from {@code attack}. Three gates, all upstream's: an invincible player is skipped,
     * a target still inside its damage immunity window is skipped, and anything 4 blocks or
     * further away is skipped. {@code invulnerableTime} is the vanilla field behind Bukkit's
     * {@code getNoDamageTicks}.
     */
    private void attack(Bot bot, LivingEntity target, Vec3 pos) {
        boolean invincible = target instanceof ServerPlayer player
                && PlayerUtils.isInvincible(player.gameMode());

        if (invincible || target.invulnerableTime >= 5 || pos.distanceTo(target.position()) >= 4) {
            return;
        }

        bot.attack(target);
    }

    @Override
    public void onBotDeath(BotDeathEvent event) {
        if (!drops) {
            event.getDrops().clear();
        }
    }

    /**
     * Cancels a hit that a raised shield should have stopped.
     *
     * <p>Ported from {@code onPlayerDamage}. The dot product is the check: it compares the
     * direction from the attacker to the bot against the way the bot is looking, so a shield only
     * works against attacks from roughly the front. {@code -0.1} is upstream's threshold and is
     * slightly generous — a hit from just past 90 degrees still blocks.
     */
    @Override
    public void onPlayerDamage(BotDamageByPlayerEvent event) {
        Bot bot = event.getBot();
        ServerPlayer player = event.getPlayer();

        Vec3 toBot = bot.position().subtract(player.position());

        if (toBot.lengthSqr() == 0) {
            return;
        }

        double dot = toBot.normalize().dot(bot.getLookAngle());

        if (bot.isBotBlocking() && dot >= -0.1) {
            ServerLevel level = (ServerLevel) bot.level();
            level.playSound(null, bot.blockPosition(), SoundEvents.SHIELD_BLOCK.value(),
                    SoundSource.PLAYERS, 1f, 1f);
            event.setCancelled(true);
        }
    }

    /**
     * The MLG. Left empty until Task 14 builds the placement predicates it needs.
     *
     * <p>Upstream's version searches {@code event.getStandingOn()} for a block it can put water
     * (or twisting vines) on, places it, cancels the fall damage, and schedules picking the water
     * back up five ticks later.
     */
    @Override
    public void onFallDamage(BotFallDamageEvent event) {
        // Task 14.
    }

    private static BlockState stateAt(ServerLevel level, Vec3 pos) {
        return level.getBlockState(BlockPos.containing(pos));
    }
}
```

Add `floorX` and `floorZ` to `BotMath` next to `floorY`, all three being `Mth.floor` of one
component. They exist as named helpers because upstream's `getBlockX()` calls are everywhere and
`Mth.floor(pos.x)` at 40 call sites reads worse than `BotMath.floorX(pos)`.

- [ ] **Step 6: Install the agent and add the config commands**

In `TerminatorPlus`'s constructor, after registering on the bus:

```java
        REGISTRY.setAgent(new LegacyAgent(REGISTRY));
```

In `BotCommands.register`:

```java
        root.then(Commands.literal("goal")
                .executes(BotCommands::showGoal)
                .then(Commands.argument("goal", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (TargetGoal goal : TargetGoal.values()) {
                                builder.suggest(goal.name().toLowerCase().replace("_", ""));
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommands::setGoal)));

        root.then(Commands.literal("agent")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            TerminatorPlus.registry().agent()
                                    .setEnabled(BoolArgumentType.getBool(ctx, "enabled"));
                            return 1;
                        })));

        root.then(Commands.literal("drops")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            TerminatorPlus.registry().agent()
                                    .setDrops(BoolArgumentType.getBool(ctx, "enabled"));
                            return 1;
                        })));

        root.then(Commands.literal("offsets")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            if (TerminatorPlus.registry().agent() instanceof LegacyAgent agent) {
                                agent.offsets = BoolArgumentType.getBool(ctx, "enabled");
                            }
                            return 1;
                        })));
```

and the two goal handlers:

```java
    private static int showGoal(CommandContext<CommandSourceStack> ctx) {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        TargetGoal goal = agent.targeting().getTargetType();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Goal: " + goal.name() + " — " + goal.description()), false);
        return 1;
    }

    private static int setGoal(CommandContext<CommandSourceStack> ctx) {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "goal");
        TargetGoal goal = TargetGoal.from(name);

        if (goal == null) {
            ctx.getSource().sendFailure(Component.literal("No such goal: '" + name + "'"));
            return 0;
        }

        agent.targeting().setTargetType(goal);
        ctx.getSource().sendSuccess(() -> Component.literal("Goal set to " + goal.name()), true);
        return 1;
    }
```

`/tplus region` is deliberately left out until Task 24 — it takes seven arguments and there is
nothing to test it against until the agent is complete.

- [ ] **Step 7: Run everything**

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: `BUILD SUCCESSFUL` and `All 59 required tests passed :)`, exit 0.

If `a_bot_closes_the_distance_to_a_survival_player` fails with the bot not moving at all, check in
this order: is the agent enabled; does `locateTarget` find the mock player (it is only a candidate
once `addFreshEntity` has run); and is `bot.isBotOnGround()` true (`move` returns immediately if
not, and a bot spawned at `y+1` needs a few ticks to land).

- [ ] **Step 8: Watch it work**

```bash
./gradlew runServer > run-server.log 2>&1
```

```
/gamemode survival
/tplus create Hunter
```

The bot should turn toward you and start hopping at you, and hit you when it arrives. This is the
plan's halfway point and the first time the port does the thing the plugin is for. Then check the
switches:

```
/tplus goal none
/tplus agent false
/tplus offsets false
```

With `offsets false` and several bots, they should converge on you exactly rather than spreading
into a ring — that is the visible difference the per-bot offset makes.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java
git commit -m "feat: bots locate, chase, swim toward and attack a target"
```

---
# Phase 5: Block rules

Everything the agent decides about terrain runs through `LegacyMats`, and it is 489 lines of
hand-maintained `Material` lists plus three dense property predicates. Correction 3 revised the
spec's estimate: the sets genuinely collapse into tags, the predicates genuinely do not.

This phase is where tags earn their place. Upstream's lists go stale every version — the
`Material.CHAIN` to `IRON_CHAIN` rename is exactly what broke it — and a tag absorbs that.

## Task 13: Finish `BlockRules`

Read the whole original before touching anything. The sets are in the first 240 lines, the
predicates after:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyMats.java | sed -n '23,240p'
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyMats.java | sed -n '256,282p;480,489p'
cat src/main/java/net/nuggetmc/tplus/agent/legacy/BlockRules.java
```

**How the translation works.** Upstream's `concatTypes` builds a set by scanning every `Material`
and keeping those whose `data` class matches, minus explicit exclusions, plus a name-pattern
predicate. Three different mechanisms, all of which are really asking "what category is this
block in". Each one maps to a tag, a `Block` class test, or an explicit set:

| Upstream mechanism | Translation |
|---|---|
| `Arrays.asList(Material.A, Material.B, …)` — a curated list | explicit `Set<Block>` |
| `concatTypes(Fence.class)` — a Bukkit data class | `BlockTags` lookup where one exists |
| `m.name().endsWith("_CARPET")` | `BlockTags.WOOL_CARPETS` |
| `m.name().startsWith("POTTED_")` | `BlockTags.FLOWER_POTS` |
| `m.name().endsWith("_WALL_BANNER")` | `BlockTags.BANNERS` plus a `WallBannerBlock` test |
| `mat.data == Candle.class` | `BlockTags.CANDLES` |
| no tag exists | `instanceof` on the vanilla `Block` subclass |

**One fidelity trap in `FENCE`.** Upstream wrote
`concatTypes(exclusions = {GLASS_PANE, IRON_BARS}, types = {Fence.class, Wall.class})`. In Bukkit,
`GlassPane` extends `Fence`, so *every* pane matched `Fence.class` — and only plain `GLASS_PANE` and
`IRON_BARS` were excluded by name. **Stained glass panes were therefore in upstream's FENCE set**,
and bots treat them as fences: something to break through at foot level rather than walk into.
`BlockTags.FENCES` plus `BlockTags.WALLS` does not include them, so the set needs the pane test
added back explicitly. This looks like a bug in upstream and it is, but it is a *load-bearing* one —
`checkNearby` scans for `FENCE` blocks at foot height before anything else.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockRules.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java`

- [ ] **Step 1: Write the failing GameTests**

Append to `BlockRuleTests`:

```java
    @GameTest
    @EmptyTemplate(floor = true)
    static void fences_include_walls_and_stained_panes_but_not_plain_glass(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.isFence(Blocks.OAK_FENCE.defaultBlockState()), "oak fence");
        helper.assertTrue(BlockRules.isFence(Blocks.NETHER_BRICK_FENCE.defaultBlockState()), "nether fence");
        helper.assertTrue(BlockRules.isFence(Blocks.COBBLESTONE_WALL.defaultBlockState()), "wall");

        // Upstream excluded GLASS_PANE and IRON_BARS by name, but every *stained* pane matched
        // Bukkit's Fence data class and stayed in. Bots treat these as fences. See the task.
        helper.assertTrue(BlockRules.isFence(Blocks.STAINED_GLASS_PANE.pick(DyeColor.WHITE)
                        .defaultBlockState()),
                "a stained pane was in upstream's FENCE set");
        helper.assertFalse(BlockRules.isFence(Blocks.GLASS_PANE.defaultBlockState()),
                "plain glass pane was excluded by name");
        helper.assertFalse(BlockRules.isFence(Blocks.IRON_BARS.defaultBlockState()),
                "iron bars were excluded by name");

        helper.assertFalse(BlockRules.isFence(Blocks.OAK_FENCE_GATE.defaultBlockState()),
                "gates are a separate set");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void gates_are_gates(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.isGate(Blocks.OAK_FENCE_GATE.defaultBlockState()), "oak gate");
        helper.assertTrue(BlockRules.isGate(Blocks.WARPED_FENCE_GATE.defaultBlockState()), "warped gate");
        helper.assertFalse(BlockRules.isGate(Blocks.OAK_FENCE.defaultBlockState()), "a fence is not a gate");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void breakable_means_the_bot_can_walk_through_it(ExtendedGameTestHelper helper) {
        // BREAK answers "is this space already clear". checkAt uses it: if the block at head
        // height is NOT in BREAK, the bot stops and mines it.
        helper.assertTrue(BlockRules.isBreak(Blocks.AIR.defaultBlockState()), "air");
        helper.assertTrue(BlockRules.isBreak(Blocks.WATER.defaultBlockState()), "water");
        helper.assertTrue(BlockRules.isBreak(Blocks.TALL_GRASS.defaultBlockState()), "tall grass");
        helper.assertTrue(BlockRules.isBreak(Blocks.SUGAR_CANE.defaultBlockState()), "sugar cane");
        helper.assertTrue(BlockRules.isBreak(Blocks.TWISTING_VINES.defaultBlockState()), "twisting vines");

        helper.assertFalse(BlockRules.isBreak(Blocks.STONE.defaultBlockState()), "stone");

        // SHORT_GRASS is in AIR but NOT in upstream's BREAK set. That asymmetry is upstream's
        // and it is easy to "tidy" away; a bot will look through short grass but will stop and
        // mine it if it is at head height.
        helper.assertFalse(BlockRules.isBreak(Blocks.SHORT_GRASS.defaultBlockState()),
                "short grass is deliberately absent from BREAK");
        helper.assertTrue(BlockRules.isAir(Blocks.SHORT_GRASS.defaultBlockState()),
                "but it is present in AIR");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void no_crack_is_the_set_with_no_break_animation(ExtendedGameTestHelper helper) {
        // Fluids and air get no crack animation and are never "broken" — blockBreakEffect
        // returns immediately for them, and downMine skips its nudge.
        helper.assertTrue(BlockRules.isNoCrack(Blocks.WATER.defaultBlockState()), "water");
        helper.assertTrue(BlockRules.isNoCrack(Blocks.LAVA.defaultBlockState()), "lava");
        helper.assertTrue(BlockRules.isNoCrack(Blocks.AIR.defaultBlockState()), "air");
        helper.assertFalse(BlockRules.isNoCrack(Blocks.DIRT.defaultBlockState()), "dirt cracks");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void obstacles_are_things_a_bot_must_break_to_pass(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.isObstacle(Blocks.IRON_BARS.defaultBlockState()), "iron bars");
        helper.assertTrue(BlockRules.isObstacle(Blocks.COBWEB.defaultBlockState()), "cobweb");
        helper.assertTrue(BlockRules.isObstacle(Blocks.SWEET_BERRY_BUSH.defaultBlockState()), "berries");
        helper.assertTrue(BlockRules.isObstacle(Blocks.FLOWER_POT.defaultBlockState()), "flower pot");
        helper.assertTrue(BlockRules.isObstacle(Blocks.POTTED_CACTUS.defaultBlockState()), "potted plant");
        helper.assertTrue(BlockRules.isObstacle(Blocks.GLASS_PANE.defaultBlockState()), "glass pane");
        helper.assertTrue(BlockRules.isObstacle(Blocks.END_ROD.defaultBlockState()), "end rod");

        helper.assertFalse(BlockRules.isObstacle(Blocks.STONE.defaultBlockState()), "stone is not an obstacle");

        // Upstream listed Material.CHAIN; the block is Blocks.IRON_CHAIN in 26.2, which is the
        // single clearest argument for tags over hand-written lists — see spec §4.2.
        helper.assertTrue(BlockRules.isObstacle(Blocks.IRON_CHAIN.defaultBlockState()), "chain");
        helper.assertTrue(BlockRules.isObstacle(Blocks.LIGHTNING_ROD
                .pick(WeatheringCopper.WeatherState.UNAFFECTED).defaultBlockState()), "lightning rod");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void can_stand_on_covers_non_solid_blocks_that_still_hold_a_bot_up(ExtendedGameTestHelper helper) {
        // This is the predicate Plan A's GroundCheck approximates with the collision shape.
        // Wiring it in is Step 5 of this task.
        helper.assertTrue(BlockRules.canStandOn(Blocks.SNOW.defaultBlockState()), "snow layer");
        helper.assertTrue(BlockRules.canStandOn(Blocks.LADDER.defaultBlockState()), "ladder");
        helper.assertTrue(BlockRules.canStandOn(Blocks.SCAFFOLDING.defaultBlockState()), "scaffolding");
        helper.assertTrue(BlockRules.canStandOn(Blocks.LILY_PAD.defaultBlockState()), "lily pad");
        helper.assertTrue(BlockRules.canStandOn(Blocks.CARPET.pick(DyeColor.WHITE).defaultBlockState()),
                "carpet");
        helper.assertTrue(BlockRules.canStandOn(Blocks.POTTED_CACTUS.defaultBlockState()), "potted plant");
        helper.assertTrue(BlockRules.canStandOn(Blocks.SKELETON_SKULL.defaultBlockState()), "skull");
        helper.assertTrue(BlockRules.canStandOn(Blocks.CANDLE.defaultBlockState()), "candle");

        // Upstream's one explicit exclusion: a piston head is a _HEAD by name but is not
        // something you stand on top of.
        helper.assertFalse(BlockRules.canStandOn(Blocks.PISTON_HEAD.defaultBlockState()), "piston head");
        helper.assertFalse(BlockRules.canStandOn(Blocks.AIR.defaultBlockState()), "air");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void instant_break_blocks_skip_the_crack_animation(ExtendedGameTestHelper helper) {
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.TALL_GRASS.defaultBlockState()), "tall grass");
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.DEAD_BUSH.defaultBlockState()), "dead bush");
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.WHEAT.defaultBlockState()), "wheat");
        helper.assertTrue(BlockRules.isInstantBreak(Blocks.OAK_SAPLING.defaultBlockState()), "sapling");
        helper.assertFalse(BlockRules.isInstantBreak(Blocks.STONE.defaultBlockState()), "stone");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void nonsolid_lists_blocks_that_look_solid_but_are_not_footing(ExtendedGameTestHelper helper) {
        // Used by checkNearby to decide whether the block under the bot's footing is worth
        // breaking. Excludes anything that cannot exist without support (rails, crops).
        helper.assertTrue(BlockRules.isNonSolid(Blocks.COBWEB.defaultBlockState()), "cobweb");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.LADDER.defaultBlockState()), "ladder");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.VINE.defaultBlockState()), "vine");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.POWDER_SNOW.defaultBlockState()), "powder snow");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.WALL_TORCH.defaultBlockState()), "wall torch");
        helper.assertTrue(BlockRules.isNonSolid(Blocks.NETHER_PORTAL.defaultBlockState()), "nether portal");

        helper.assertFalse(BlockRules.isNonSolid(Blocks.STONE.defaultBlockState()), "stone");
        helper.assertFalse(BlockRules.isNonSolid(Blocks.RAIL.defaultBlockState()),
                "rails are excluded: they cannot exist without support");

        helper.succeed();
    }
```

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew runGameTestServer
```

Expected: compile failure, `cannot find symbol: method isFence`.

- [ ] **Step 3: Add the remaining sets and predicates**

Append to `BlockRules`, and delete the "Partial" paragraph from its class javadoc:

```java
    /**
     * Blocks a bot considers already-clear space, so it does not try to mine them.
     *
     * <p>Upstream's BREAK set. Note that it is **not** the same as {@link #isAir}: SHORT_GRASS is
     * in AIR but not here, so a bot sees through short grass and still mines it at head height.
     * Kept.
     */
    private static final Set<Block> BREAK = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR,
            Blocks.WATER, Blocks.LAVA,
            Blocks.TALL_GRASS,
            Blocks.VINE,
            Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.SUGAR_CANE,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.WEEPING_VINES,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.SUNFLOWER, Blocks.TORCHFLOWER, Blocks.PITCHER_PLANT,
            Blocks.FIRE, Blocks.SOUL_FIRE);

    /** Blocks that get no break animation, and are never "broken" by the mining code. */
    private static final Set<Block> NO_CRACK = Set.of(
            Blocks.WATER, Blocks.LAVA,
            Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR);

    /** Blocks a bot may safely overwrite when it places cobblestone. Upstream's SPAWN set. */
    private static final Set<Block> SPAWN = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR,
            Blocks.TALL_GRASS, Blocks.SNOW,
            Blocks.VINE,
            Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.SUGAR_CANE,
            Blocks.TWISTING_VINES, Blocks.WEEPING_VINES,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
            Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.SUNFLOWER,
            Blocks.FIRE, Blocks.SOUL_FIRE);

    /**
     * Upstream's FALL set: SPAWN plus water, minus fire.
     *
     * <p>It has no caller anywhere on {@code master} — checked with
     * {@code git grep 'LegacyMats.FALL'} — so it is not ported. Recorded here because the name
     * suggests it should be involved in fall damage and it is not; {@code Bot.isFallBlocked} uses
     * {@code BotUtils.NO_FALL} instead.
     */

    /** Blocks that must be broken rather than walked through. Upstream's OBSTACLES set. */
    private static final Set<Block> OBSTACLE_BLOCKS = Set.of(
            Blocks.IRON_BARS,
            // Not Blocks.CHAIN: correction 5. This is the rename spec §4.2 cites.
            Blocks.IRON_CHAIN,
            Blocks.END_ROD,
            Blocks.COBWEB,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.FLOWER_POT,
            Blocks.GLASS_PANE);

    /**
     * Blocks that look like footing but are not, so breaking the block below them is pointless.
     *
     * <p>Upstream's NONSOLID set, with its comment: "We exclude blocks that cannot exist without
     * a solid block below (such as rails or crops)".
     */
    private static final Set<Block> NONSOLID_BLOCKS = Set.of(
            Blocks.COBWEB,
            Blocks.END_GATEWAY, Blocks.END_PORTAL, Blocks.NETHER_PORTAL,
            Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
            Blocks.GLOW_LICHEN, Blocks.HANGING_ROOTS,
            Blocks.POWDER_SNOW,
            Blocks.SCULK_VEIN,
            Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK,
            Blocks.LADDER, Blocks.VINE,
            Blocks.WALL_TORCH, Blocks.SOUL_WALL_TORCH, Blocks.REDSTONE_WALL_TORCH,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT);

    /** Blocks that break in one hit, so the mining loop skips straight to destroying them. */
    private static final Set<Block> INSTANT_BREAK_BLOCKS = Set.of(
            Blocks.TALL_GRASS, Blocks.SHORT_GRASS,
            Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.KELP_PLANT,
            Blocks.DEAD_BUSH,
            Blocks.WHEAT, Blocks.POTATOES, Blocks.CARROTS, Blocks.BEETROOTS,
            Blocks.SEA_PICKLE);

    /**
     * Blocks upstream's {@code canStandOn} listed individually, as opposed to by name pattern.
     */
    private static final Set<Block> STANDABLE = Set.of(
            Blocks.END_ROD, Blocks.FLOWER_POT,
            Blocks.REPEATER, Blocks.COMPARATOR,
            Blocks.SNOW, Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING,
            Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.BIG_DRIPLEAF,
            Blocks.CHORUS_FLOWER, Blocks.CHORUS_PLANT, Blocks.COCOA,
            Blocks.LILY_PAD, Blocks.SEA_PICKLE);

    public static boolean isBreak(BlockState state) {
        return BREAK.contains(state.getBlock());
    }

    public static boolean isNoCrack(BlockState state) {
        return NO_CRACK.contains(state.getBlock());
    }

    public static boolean isSpawn(BlockState state) {
        return SPAWN.contains(state.getBlock());
    }

    public static boolean isInstantBreak(BlockState state) {
        // BlockTags has no SAPLINGS constant; the tag exists but is only reachable through
        // BlockItemTags.SAPLINGS.block(), which is how BlockTags declares its own aliases.
        return INSTANT_BREAK_BLOCKS.contains(state.getBlock())
                || state.is(BlockItemTags.SAPLINGS.block())
                || state.is(BlockTags.CORALS)
                || state.is(BlockTags.WALL_CORALS)
                || state.is(BlockTags.FLOWER_POTS);
    }

    /**
     * Fences and walls — and, faithfully, stained glass panes.
     *
     * <p>See the task notes: upstream matched Bukkit's {@code Fence} data class, which every pane
     * extends, and excluded only plain {@code GLASS_PANE} and {@code IRON_BARS} by name. The pane
     * test restores that. {@code StainedGlassPaneBlock} extends {@code IronBarsBlock}, so the
     * class test catches the stained panes and the two named exclusions remove the rest.
     */
    public static boolean isFence(BlockState state) {
        Block block = state.getBlock();

        if (block == Blocks.GLASS_PANE || block == Blocks.IRON_BARS) {
            return false;
        }

        return state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
                || block instanceof IronBarsBlock;
    }

    public static boolean isGate(BlockState state) {
        return state.is(BlockTags.FENCE_GATES);
    }

    public static boolean isLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES);
    }

    public static boolean isObstacle(BlockState state) {
        // LightningRodBlock rather than a constant: rods are a weathering-copper family now,
        // so there is no single Blocks.LIGHTNING_ROD to compare against (correction 5).
        return OBSTACLE_BLOCKS.contains(state.getBlock())
                || state.is(BlockTags.FLOWER_POTS)
                || state.getBlock() instanceof LightningRodBlock
                || state.getBlock() instanceof IronBarsBlock;
    }

    public static boolean isNonSolid(BlockState state) {
        return NONSOLID_BLOCKS.contains(state.getBlock())
                || state.is(BlockTags.BUTTONS)
                || state.is(BlockTags.WALL_SIGNS)
                || state.is(BlockTags.ALL_HANGING_SIGNS)
                || state.is(BlockTags.BANNERS)
                || state.is(BlockTags.WALL_CORALS)
                || state.getBlock() instanceof LeverBlock;
    }

    /**
     * Solidity, as upstream meant it.
     *
     * <p>Upstream was {@code mat.isSolid() || SOLID_MATERIALS.contains(mat)}, and
     * {@code SOLID_MATERIALS} is declared and never populated anywhere on {@code master} — check
     * with {@code git grep SOLID_MATERIALS}. So this is just the vanilla predicate.
     */
    public static boolean isSolid(BlockState state) {
        return state.isSolid();
    }

    /**
     * Non-solid blocks that still hold an entity up.
     *
     * <p>Ported from {@code canStandOn}. Upstream's name patterns become tags where one exists:
     * {@code endsWith("_CARPET")} is {@code WOOL_CARPETS}, {@code startsWith("POTTED_")} is
     * {@code FLOWER_POTS}, {@code data == Candle.class} is {@code CANDLES}. Heads and skulls have
     * no tag, so they are a class test — and {@code PISTON_HEAD} is excluded by name, exactly as
     * upstream did, because it matches {@code _HEAD} but is not footing.
     */
    public static boolean canStandOn(BlockState state) {
        Block block = state.getBlock();

        if (block == Blocks.PISTON_HEAD) {
            return false;
        }

        return STANDABLE.contains(block)
                || state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.FLOWER_POTS)
                || state.is(BlockTags.CANDLES)
                || block instanceof SkullBlock
                || block instanceof WallSkullBlock;
    }
```

Add imports: `net.minecraft.tags.BlockItemTags`, `net.minecraft.tags.BlockTags`,
`net.minecraft.world.level.block.IronBarsBlock`, `net.minecraft.world.level.block.LightningRodBlock`,
`net.minecraft.world.level.block.LeverBlock`, `net.minecraft.world.level.block.SkullBlock`,
`net.minecraft.world.level.block.WallSkullBlock`.

Before running, verify the block constants that upstream named under old spellings, plus the two
class names:

```bash
grep -nE "public static final Block (BEETROOTS|WHEAT|TRIPWIRE|CAVE_VINES|POTTED_CACTUS|WHITE_STAINED_GLASS_PANE) =" /tmp/mcsrc/net/minecraft/world/level/block/Blocks.java
grep -nE "^public class (SkullBlock|WallSkullBlock|LeverBlock)" /tmp/mcsrc/net/minecraft/world/level/block/{SkullBlock,WallSkullBlock,LeverBlock}.java
```

Upstream listed `Material.WHEAT_SEEDS`, `BEETROOT_SEEDS` and `CARROTS`/`POTATOES` in
`INSTANT_BREAK`. Those are *items*; the planted blocks are `WHEAT` and `BEETROOTS`. Upstream's set
was therefore partly populated with item constants that never matched a block, which is why the
translation above uses the block names — a deviation that makes the set do what it was clearly
meant to do, and the only one in this task. Flag it in the commit message.

- [ ] **Step 4: Run the GameTests and confirm they pass**

```bash
./gradlew runGameTestServer
```

Expected: `All 67 required tests passed :)`.

- [ ] **Step 5: Close Plan A's known approximation**

`GroundCheck.standableBox` uses a block's **collision shape** as a stand-in for upstream's
`LegacyMats.isSolid(type) || LegacyMats.canStandOn(type)`. Plan A measured both options and kept
collision as the less-wrong one, and recorded the exact predicate as owed to Plan B. It exists now.

Read Plan A's version and the original side by side:

```bash
sed -n '40,90p' src/main/java/net/nuggetmc/tplus/motion/GroundCheck.java
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java | sed -n '529,590p'
```

Change the filter in `standableBox` to the real predicate:

```java
    private static AABB standableBox(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        // Upstream's test, now that BlockRules exists: a block is footing if it is solid, or if
        // it is one of the non-solid blocks that still holds an entity up. Plan A approximated
        // this with a non-empty collision shape, which differed on 11 blocks in the measurement
        // it recorded.
        if (!BlockRules.isSolid(state) && !BlockRules.canStandOn(state)) {
            return null;
        }

        VoxelShape shape = state.getCollisionShape(level, pos);

        // Still needed, and for the reason Plan A found the hard way: bounds() throws on an
        // empty shape, and canStandOn admits blocks whose collision shape is empty — a carpet,
        // a lily pad, a candle. Those get a flat box at the block's base instead.
        if (shape.isEmpty()) {
            return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY(), pos.getZ() + 1);
        }

        return shape.bounds().move(pos);
    }
```

The empty-shape branch is new and needs a decision recorded. Upstream used Bukkit's
`Block.getBoundingBox()`, which for a carpet returns a zero-height box at the block's base —
that is what the fallback reproduces. Returning null instead would mean a bot never stands on a
carpet, which upstream's `canStandOn` explicitly allows.

Then re-run Plan A's ground tests specifically, because this is the one change in the plan that can
regress physics:

```bash
./gradlew runGameTestServer
```

Expected: every `bot.lifecycle` test still passes. If a standing-on test regresses, **stop and
measure** the way Plan A did rather than adjusting the predicate by intuition — that measurement is
what caught the outline-shape change being a regression.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java
git commit -m "feat: complete BlockRules; use the real standable predicate in GroundCheck"
```

---

## Task 14: `BlockPlacement` and the MLG

Three predicates that decide where a bot can put water or twisting vines to survive a fall, and the
`onFallDamage` handler that uses them. Correction 3: these do not reduce to tags — they are
`BlockState` property decision trees, and they are the difference between a bot that survives a
fall and one that splatters.

Read all four originals in full:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyMats.java | sed -n '283,479p'
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '330,410p'
```

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockPlacement.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` — `onFallDamage`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java`

- [ ] **Step 1: Write the failing GameTests**

Append to `BlockRuleTests`:

```java
    @GameTest
    @EmptyTemplate(floor = true)
    static void water_goes_on_a_plain_solid_block(ExtendedGameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);

        helper.assertTrue(BlockPlacement.canPlaceWater(helper.getLevel(), pos, OptionalDouble.empty()),
                "stone takes water on top");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void water_does_not_go_on_a_dry_top_slab(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));

        // A top slab is solid, but water placed against it flows into the gap underneath
        // instead of forming a landing surface. Upstream excluded it unless already waterlogged.
        helper.assertFalse(BlockPlacement.canPlaceWater(helper.getLevel(),
                        helper.absolutePos(relative), OptionalDouble.empty()),
                "a dry top slab must be refused");

        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
                .setValue(BlockStateProperties.WATERLOGGED, true));

        helper.assertTrue(BlockPlacement.canPlaceWater(helper.getLevel(),
                        helper.absolutePos(relative), OptionalDouble.empty()),
                "a waterlogged top slab already has water and is fine");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void a_bottom_slab_depends_on_where_the_bot_is(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, Blocks.STONE_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HALF, Half.BOTTOM));

        // Upstream's most obscure rule: a dry bottom-half stair is refused UNLESS the falling
        // entity's own Y block matches the stair's, in which case the bot is already inside the
        // upper half of that block and the water will sit at its feet. The OptionalDouble is
        // that entity Y — absent means "no entity context", which refuses.
        helper.assertFalse(BlockPlacement.canPlaceWater(helper.getLevel(), pos, OptionalDouble.empty()),
                "no entity context: refuse");
        helper.assertFalse(BlockPlacement.canPlaceWater(helper.getLevel(), pos,
                        OptionalDouble.of(pos.getY() + 5)),
                "entity well above: refuse");
        helper.assertTrue(BlockPlacement.canPlaceWater(helper.getLevel(), pos,
                        OptionalDouble.of(pos.getY() + 0.5)),
                "entity inside the same block: allow");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void water_goes_on_a_snow_layer_but_not_in_mid_air(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Blocks.SNOW);

        helper.assertTrue(BlockPlacement.canPlaceWater(helper.getLevel(),
                        helper.absolutePos(relative), OptionalDouble.empty()),
                "a snow layer is a valid landing");

        helper.assertFalse(BlockPlacement.canPlaceWater(helper.getLevel(),
                        helper.absolutePos(new BlockPos(3, 3, 3)), OptionalDouble.empty()),
                "air is not a valid landing");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void twisting_vines_need_a_full_block_face(ExtendedGameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.NETHERRACK);
        helper.assertTrue(BlockPlacement.canPlaceTwistingVines(helper.getLevel(),
                helper.absolutePos(new BlockPos(1, 1, 1))), "netherrack");

        helper.setBlock(new BlockPos(2, 1, 1), Blocks.OAK_FENCE);
        helper.assertFalse(BlockPlacement.canPlaceTwistingVines(helper.getLevel(),
                helper.absolutePos(new BlockPos(2, 1, 1))), "a fence has no full top face");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void should_replace_takes_partial_height_blocks_only(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        double insideY = pos.getY() + 0.4;

        // shouldReplace answers "does the water go INTO this block, or on top of it?" — it is
        // what decides between ground and ground.above() in onFallDamage. Upstream's list is
        // partial-height shapes, NOT "non-solid blocks".
        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
        helper.assertTrue(BlockPlacement.shouldReplace(helper.getLevel(), pos, insideY, false),
                "a bottom slab leaves the top half free, so water goes in");

        helper.setBlock(relative, Blocks.STONE);
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, insideY, false),
                "stone is built on top of");

        // A snow layer looks like the obvious candidate and is deliberately absent from
        // upstream's list. An earlier draft of this plan asserted the opposite.
        helper.setBlock(relative, Blocks.SNOW);
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, insideY, false),
                "a snow layer is NOT in upstream's replace list");

        helper.succeed();
    }

    @GameTest
    @EmptyTemplate(floor = true)
    static void should_replace_has_two_leading_gates(ExtendedGameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));

        // Gate one: the bot's own block Y must equal the block's, so a bot still well above the
        // slab builds on top of it rather than into it.
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, pos.getY() + 4, false),
                "an entity four blocks up must not replace");

        // Gate two: the Nether never replaces, because twisting vines need a surface to sit on.
        helper.assertFalse(BlockPlacement.shouldReplace(helper.getLevel(), pos, pos.getY() + 0.4, true),
                "the Nether never replaces");

        helper.succeed();
    }
```

Add imports: `java.util.OptionalDouble`,
`net.minecraft.world.level.block.state.properties.BlockStateProperties`,
`net.minecraft.world.level.block.state.properties.Half`,
`net.minecraft.world.level.block.state.properties.SlabType`,
`net.nuggetmc.tplus.agent.legacy.BlockPlacement`.

And the behaviour test, appended to `AgentTests`:

```java
    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x30x9", floor = true)
    static void a_falling_bot_clutches_with_water(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(4, 25, 4));
        bot.setGameMode(GameType.SURVIVAL);

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(4.5, 1, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        float before = bot.getHealth();
        run(registry, bot, 120);

        // The MLG: onFallDamage finds the floor, places water, cancels the damage, and picks
        // the water back up five ticks later. Surviving a 24-block fall is the assertion.
        helper.assertTrue(bot.isAlive(), "the bot must survive the fall");
        helper.assertValueEqual(bot.getHealth(), before, "the fall damage must be cancelled");

        registry.reset();
        helper.succeed();
    }
```

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew runGameTestServer
```

Expected: compile failure, `cannot find symbol: class BlockPlacement`.

- [ ] **Step 3: Write `BlockPlacement`**

Upstream's three methods, translated property by property. `Optional<Double>` becomes
`OptionalDouble` — upstream used Guava's `com.google.common.base.Optional`, which is a dependency
worth losing.

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.OptionalDouble;
import java.util.Set;

/**
 * Where a bot can put water, or twisting vines, to survive a fall.
 *
 * <p>Ported from {@code LegacyMats.canPlaceWater}, {@code canPlaceTwistingVines} and
 * {@code shouldReplace}. Split out of {@link BlockRules} because these are property decision
 * trees rather than category tests, and because 170 lines of nested state checks in the same file
 * as the sets would make both unreviewable (plan correction 3).
 *
 * <p>The shape of each method is upstream's: a solid branch that lists the shapes water will
 * *not* sit on, and a non-solid branch that lists the few blocks it will.
 */
public final class BlockPlacement {

    private BlockPlacement() {
    }

    /**
     * Whether water placed at {@code pos} gives a bot something to land in.
     *
     * @param entityY the falling bot's Y, if known. Upstream passed it for the real MLG and
     *                left it absent for the speculative pre-MLG scan, and one rule reads it:
     *                a dry bottom-half stair only counts when the bot is already inside that
     *                same block.
     */
    public static boolean canPlaceWater(ServerLevel level, BlockPos pos, OptionalDouble entityY) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (BlockRules.isSolid(state)) {
            boolean waterlogged = state.getValueOrElse(BlockStateProperties.WATERLOGGED, false);

            // A vertical chain: water flows straight past it. Blocks.IRON_CHAIN, not
            // Blocks.CHAIN — correction 5.
            if (block == Blocks.IRON_CHAIN && !waterlogged
                    && state.getValueOrElse(BlockStateProperties.AXIS, Direction.Axis.Y) == Direction.Axis.Y) {
                return false;
            }

            // Leaves, mangrove roots, bars and panes: water passes through unless already in.
            if ((state.is(BlockTags.LEAVES) || block == Blocks.MANGROVE_ROOTS
                    || block instanceof IronBarsBlock) && !waterlogged) {
                return false;
            }

            if (state.getValueOrElse(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM) == SlabType.TOP
                    && !waterlogged) {
                return false;
            }

            if (state.is(BlockTags.STAIRS) && !waterlogged) {
                Half half = state.getValueOrElse(BlockStateProperties.HALF, Half.BOTTOM);

                if (half == Half.TOP) {
                    return false;
                }

                // The obscure one. A dry bottom stair is refused unless the bot's own block Y
                // matches the stair's, meaning it is already inside the empty upper half.
                if (half == Half.BOTTOM
                        && (entityY.isEmpty() || (int) entityY.getAsDouble() != pos.getY())) {
                    return false;
                }
            }

            if ((state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) && !waterlogged) {
                return false;
            }

            // instanceof, not identity: lightning rods are a weathering-copper family in 26.2
            // and there is no single Blocks.LIGHTNING_ROD constant (correction 5).
            if (block instanceof LightningRodBlock && !waterlogged) {
                Direction facing = state.getValueOrElse(BlockStateProperties.FACING, Direction.UP);

                if (facing == Direction.UP || facing == Direction.DOWN) {
                    return false;
                }
            }

            if (state.is(BlockTags.TRAPDOORS) && !waterlogged) {
                Half half = state.getValueOrElse(BlockStateProperties.HALF, Half.BOTTOM);
                boolean open = state.getValueOrElse(BlockStateProperties.OPEN, false);

                if (half == Half.TOP || (half == Half.BOTTOM && open)) {
                    return false;
                }
            }

            return true;
        }

        // Non-solid: only the handful of blocks that still hold water at their base.
        return state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.CANDLES)
                || state.is(BlockTags.FLOWER_POTS)
                || isHead(state)
                || STILL_HOLDS_WATER.contains(state.getBlock());
    }

    /**
     * Upstream's non-solid switch in {@code canPlaceWater}, as a set.
     *
     * <p>Read by {@code canPlaceWater} only. {@code shouldReplace} has its own, different list —
     * see {@link #REPLACEABLE_BY_WATER} and the note there.
     */
    private static final Set<Block> STILL_HOLDS_WATER = Set.of(
            Blocks.SNOW,
            Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
            Blocks.CHORUS_FLOWER, Blocks.CHORUS_PLANT,
            Blocks.COCOA,
            Blocks.LILY_PAD, Blocks.SEA_PICKLE,
            Blocks.END_ROD, Blocks.FLOWER_POT,
            Blocks.SCAFFOLDING,
            Blocks.COMPARATOR, Blocks.REPEATER);

    /**
     * Whether twisting vines placed at {@code pos} will hold, for a Nether clutch.
     *
     * <p>Stricter than water, because vines need a solid full face beneath them. Upstream wrote
     * this as ~25 sequential rejections; vanilla asks the question directly.
     */
    public static boolean canPlaceTwistingVines(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (BlockRules.isSolid(state)) {
            return vinesHoldOnSolid(state);
        }

        if (state.getBlock() == Blocks.SNOW) {
            // Upstream: exactly 1 or 8 layers. One layer leaves a full block of space above,
            // eight is a full block — anything between leaves a partial gap the vine falls into.
            int layers = state.getValueOrElse(BlockStateProperties.LAYERS, 1);
            return layers == 1 || layers == 8;
        }

        return state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.FLOWER_POTS)
                || isHead(state);
    }

    /**
     * Upstream's rejection list for the solid branch of {@code canPlaceTwistingVines}.
     *
     * <p>Every entry is a shape that leaves the top of its block space unusable. The obvious
     * compression is {@code state.isFaceSturdy(level, pos, Direction.UP)} — "is the top face a
     * full square" — and it is **wrong**: it disagrees with upstream on farmland, honey blocks,
     * leaves and the end portal frame, all of which are full cubes that upstream rejects anyway.
     * Measured, not guessed; keep the list.
     *
     * <p>Where a tag is exactly equivalent to upstream's test it is used, because a tag cannot go
     * stale. The rest is upstream's 35-entry switch, verbatim.
     */
    private static boolean vinesHoldOnSolid(BlockState state) {
        Block block = state.getBlock();

        if (state.is(BlockTags.LEAVES) || isCoral(state)
                || block instanceof IronBarsBlock
                || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)
                || state.is(BlockTags.BANNERS)
                || state.is(BlockTags.BEDS)
                || state.is(BlockTags.CANDLE_CAKES)
                || state.is(BlockTags.DOORS)
                || state.is(BlockTags.FENCE_GATES)) {
            return false;
        }

        if (state.is(BlockTags.SLABS)
                && state.getValueOrElse(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM) == SlabType.BOTTOM) {
            return false;
        }

        if (state.is(BlockTags.STAIRS)
                && state.getValueOrElse(BlockStateProperties.HALF, Half.BOTTOM) == Half.BOTTOM) {
            return false;
        }

        if (state.is(BlockTags.TRAPDOORS)) {
            boolean bottom = state.getValueOrElse(BlockStateProperties.HALF, Half.BOTTOM) == Half.BOTTOM;

            if (bottom || state.getValueOrElse(BlockStateProperties.OPEN, false)) {
                return false;
            }
        }

        // Pistons by identity rather than class: the base and head block classes were renamed,
        // and the three constants are stable. A head must point up; an extended base must point
        // down.
        if (block == Blocks.PISTON_HEAD
                && state.getValueOrElse(BlockStateProperties.FACING, Direction.UP) != Direction.UP) {
            return false;
        }

        if ((block == Blocks.PISTON || block == Blocks.STICKY_PISTON)
                && state.getValueOrElse(BlockStateProperties.EXTENDED, false)
                && state.getValueOrElse(BlockStateProperties.FACING, Direction.DOWN) != Direction.DOWN) {
            return false;
        }

        return !NO_VINE_SURFACE.contains(block) && !(block instanceof LightningRodBlock);
    }

    /** Upstream's 35-entry switch in {@code canPlaceTwistingVines}, minus the tagged families. */
    private static final Set<Block> NO_VINE_SURFACE = Set.of(
            Blocks.POINTED_DRIPSTONE,
            Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
            Blocks.AMETHYST_CLUSTER,
            Blocks.BAMBOO, Blocks.CACTUS,
            Blocks.DRAGON_EGG, Blocks.TURTLE_EGG,
            Blocks.IRON_CHAIN, Blocks.IRON_BARS,
            Blocks.LANTERN, Blocks.SOUL_LANTERN,
            Blocks.ANVIL, Blocks.BREWING_STAND,
            Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.TRAPPED_CHEST,
            Blocks.ENCHANTING_TABLE, Blocks.GRINDSTONE, Blocks.LECTERN, Blocks.STONECUTTER,
            Blocks.BELL, Blocks.CAKE,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.CAULDRON, Blocks.COMPOSTER, Blocks.CONDUIT,
            Blocks.END_PORTAL_FRAME, Blocks.FARMLAND, Blocks.DAYLIGHT_DETECTOR,
            Blocks.HONEY_BLOCK, Blocks.HOPPER,
            Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER);

    /** Coral in any of upstream's three name forms: coral, coral fan, coral wall fan. */
    private static boolean isCoral(BlockState state) {
        return state.is(BlockTags.CORALS) || state.is(BlockTags.CORAL_PLANTS)
                || state.is(BlockTags.WALL_CORALS);
    }

    /**
     * Whether the clutch block is placed *into* {@code pos} rather than on top of it.
     *
     * <p>Ported from {@code shouldReplace}. {@code onFallDamage} calls it to choose between
     * {@code groundLoc} and {@code groundLoc.above()}: a snow layer or a carpet is replaced where
     * it is, a solid block is built on.
     */
    public static boolean shouldReplace(ServerLevel level, BlockPos pos, double entityY, boolean nether) {
        // Two leading gates, both upstream's, both easy to lose in a rewrite. The bot's own block
        // Y must equal the block's, so this only ever replaces a block the bot is already inside;
        // and nothing is ever replaced in the Nether, because twisting vines need a surface to
        // sit on rather than a space to fill.
        if ((int) entityY != pos.getY() || nether) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        boolean waterlogged = state.getValueOrElse(BlockStateProperties.WATERLOGGED, false);

        if (isCoral(state)) {
            return true;
        }

        if (state.is(BlockTags.SLABS)
                && state.getValueOrElse(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM) == SlabType.BOTTOM) {
            return true;
        }

        // Either half, unlike canPlaceWater's stair rule, which distinguishes them. Upstream
        // tested only waterlogging here; the two methods disagree on purpose.
        if (state.is(BlockTags.STAIRS) && !waterlogged) {
            return true;
        }

        if (block == Blocks.IRON_CHAIN && !waterlogged) {
            return true;
        }

        if (state.is(BlockTags.CANDLES)) {
            return true;
        }

        if (state.is(BlockTags.TRAPDOORS) && !waterlogged) {
            return true;
        }

        return REPLACEABLE_BY_WATER.contains(block) || block instanceof LightningRodBlock;
    }

    /**
     * Upstream's 17-entry switch in {@code shouldReplace}.
     *
     * <p>Blocks that leave enough of their block space empty for water to occupy it. The set is
     * emphatically **not** "non-solid blocks": carpets, snow layers, flower pots and heads are all
     * absent, and full-height stairs are present. An earlier draft of this plan reused
     * {@code STILL_HOLDS_WATER} here and got every one of those wrong.
     */
    private static final Set<Block> REPLACEABLE_BY_WATER = Set.of(
            Blocks.POINTED_DRIPSTONE,
            Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
            Blocks.AMETHYST_CLUSTER,
            Blocks.SEA_PICKLE,
            Blocks.LANTERN, Blocks.SOUL_LANTERN,
            Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.TRAPPED_CHEST,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.CONDUIT,
            Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER);

    /** A head or skull, wall-mounted or not. No tag covers these. */
    private static boolean isHead(BlockState state) {
        Block block = state.getBlock();
        return block instanceof SkullBlock || block instanceof WallSkullBlock;
    }
}
```

**Both of these were compressed in an earlier draft of this plan, and both compressions were
wrong.** The record, because the same shortcuts will look tempting again:

- `shouldReplace` was written as "solid, and not face-sturdy". Upstream's real logic leads with two
  gates — the bot's block Y must match the block's, and the Nether always returns false — and then
  consults an explicit list of partial-height shapes. The draft omitted both gates, added carpets,
  snow and flower pots that are **not** in upstream's list, and dropped the full-height stairs that
  are.
- `canPlaceTwistingVines`' solid branch was written as `isFaceSturdy(UP)`. That predicate disagrees
  with upstream's list on farmland, honey blocks, leaves and the end portal frame.

The versions above are the faithful translations. Read the originals alongside them anyway — but what
needs checking now is transcription, not judgement.

- [ ] **Step 4: Write `onFallDamage`**

Replace the stub in `LegacyAgent`:

```java
    /**
     * The MLG. Ported from {@code onFallDamage}.
     *
     * <p>Finds somewhere in {@code standingOn} that will take water (or twisting vines in the
     * Nether), places it, cancels the fall damage, and schedules picking the water back up five
     * ticks later. Cancelling is what "the clutch worked" means; if nothing takes the placement
     * the event is left alone and the bot takes the hit.
     *
     * <p>The waterlogging branch is upstream's: placing water "on" a waterloggable block means
     * setting its {@code waterlogged} property rather than replacing it, and the pickup has to
     * undo the same way.
     */
    @Override
    public void onFallDamage(BotFallDamageEvent event) {
        Bot bot = event.getBot();
        ServerLevel level = (ServerLevel) bot.level();
        boolean nether = bot.isNether();
        double yPos = bot.getY();

        bot.look(Direction.DOWN);

        Item itemType = nether ? Items.TWISTING_VINES : Items.WATER_BUCKET;
        Block placeType = nether ? Blocks.TWISTING_VINES : Blocks.WATER;
        SoundEvent sound = nether ? SoundEvents.WEEPING_VINES_PLACE : SoundEvents.BUCKET_EMPTY;

        BlockPos ground = null;

        for (BlockPos candidate : event.getStandingOn()) {
            boolean ok = nether
                    ? BlockPlacement.canPlaceTwistingVines(level, candidate)
                    : BlockPlacement.canPlaceWater(level, candidate, OptionalDouble.of(yPos));

            if (ok) {
                ground = candidate;
                break;
            }
        }

        if (ground == null) {
            return;
        }

        BlockPos pos = BlockPlacement.shouldReplace(level, ground, yPos, nether)
                ? ground
                : ground.above();

        BlockState state = level.getBlockState(pos);
        boolean waterloggable = !nether && state.hasProperty(BlockStateProperties.WATERLOGGED);
        boolean waterlogged = waterloggable
                && state.getValue(BlockStateProperties.WATERLOGGED);

        event.setCancelled(true);

        if (state.getBlock() == placeType || waterlogged) {
            return;
        }

        bot.punch();

        if (waterloggable) {
            level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.WATERLOGGED, true));
        } else {
            level.setBlockAndUpdate(pos, placeType.defaultBlockState());
        }

        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f);

        if (itemType != Items.WATER_BUCKET) {
            return;
        }

        bot.setItem(new ItemStack(Items.BUCKET));

        BlockPos pickup = pos;
        later(5, () -> {
            BlockState now = level.getBlockState(pickup);
            boolean loggedNow = now.getValueOrElse(BlockStateProperties.WATERLOGGED, false);

            if (now.getBlock() != Blocks.WATER && !loggedNow) {
                return;
            }

            bot.look(Direction.DOWN);
            bot.setItem(new ItemStack(Items.WATER_BUCKET));
            level.playSound(null, pickup, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1f, 1f);

            if (loggedNow) {
                level.setBlockAndUpdate(pickup, now.setValue(BlockStateProperties.WATERLOGGED, false));
            } else {
                level.setBlockAndUpdate(pickup, Blocks.AIR.defaultBlockState());
            }
        });
    }
```

Note `later(5, ...)` rather than a bare scheduler call: `Agent.later` records the id so
`stopAllTasks` cancels it. Upstream used a bare `runTaskLater` here and the pickup therefore
survived disabling the agent, leaving a water block behind.

Upstream's structure was `if (block != placeType && !waterlogged) { … }` wrapping the whole
placement. The early return above is the same condition inverted; keep the event cancelled either
way, which upstream also did by cancelling before the check.

- [ ] **Step 5: Run the GameTests and confirm they pass**

```bash
./gradlew runGameTestServer
```

Expected: `All 74 required tests passed :)`.

`a_falling_bot_clutches_with_water` is the one to watch. If the bot dies, check in order: does
`Bot.fallDamageCheck` fire the event at all (it needs `groundTicks != 0` and `noFallTicks == 0`,
and a fresh bot has 60 no-fall ticks); is `standingOn` non-empty when it fires; and does
`canPlaceWater` accept the floor block the template uses.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy src/gametest/java/net/nuggetmc/tplus/gametest
git commit -m "feat: add placement predicates and the water-bucket clutch"
```

---

## Task 15: `ScanOffset`

The 24-way offset enum `SurroundingScan` is built around, plus the mutable wrapper `Mining` passes
by reference. Mechanical, and worth doing properly because everything in Phases 6 and 7 names these
constants.

Read the original:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyLevel.java
```

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/ScanOffset.java`
- Test: `src/test/java/net/nuggetmc/tplus/agent/legacy/ScanOffsetTest.java`

- [ ] **Step 1: Write the failing pure test**

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure: an enum of integer offsets. */
class ScanOffsetTest {

    @Test
    void offsetsAppliedToAPositionMatchTheirDeclaration() {
        BlockPos origin = new BlockPos(10, 64, 20);

        assertEquals(new BlockPos(10, 66, 20), ScanOffset.ABOVE.apply(origin));
        assertEquals(new BlockPos(10, 63, 20), ScanOffset.BELOW.apply(origin));
        assertEquals(new BlockPos(10, 65, 20), ScanOffset.AT.apply(origin));
        assertEquals(new BlockPos(10, 64, 20), ScanOffset.AT_D.apply(origin));
        assertEquals(new BlockPos(10, 65, 19), ScanOffset.NORTH.apply(origin));
        assertEquals(new BlockPos(11, 63, 20), ScanOffset.EAST_D_2.apply(origin));
    }

    @Test
    void applyDoesNotMutateTheInput() {
        // Upstream's `offset(Location loc)` called loc.add(...), which mutates a Bukkit Location
        // in place. Several callers relied on the mutation and several were bugs because of it;
        // BlockPos is immutable, so this is pinned to make the change explicit.
        BlockPos origin = new BlockPos(0, 0, 0);
        ScanOffset.ABOVE.apply(origin);

        assertEquals(new BlockPos(0, 0, 0), origin);
    }

    @Test
    void getOffsetFindsTheConstantForADisplacement() {
        assertEquals(ScanOffset.NORTH,
                ScanOffset.getOffset(new BlockPos(0, 0, 0), new BlockPos(0, 1, -1)));
        assertNull(ScanOffset.getOffset(new BlockPos(0, 0, 0), new BlockPos(7, 7, 7)));
    }

    @Test
    void theFourNonSideConstantsAreNotSides() {
        // isSide is the negation of a four-element set, so every constant added to the enum is
        // a "side" by default. That is upstream's definition and it is easy to get wrong.
        assertFalse(ScanOffset.ABOVE.isSide());
        assertFalse(ScanOffset.BELOW.isSide());
        assertFalse(ScanOffset.AT.isSide());
        assertFalse(ScanOffset.AT_D.isSide());

        assertTrue(ScanOffset.NORTH.isSide());
        assertTrue(ScanOffset.NORTHWEST_D.isSide());
    }

    @Test
    void theFourSideGroupsAreExactlyTheCardinalsAtEachHeight() {
        assertTrue(ScanOffset.NORTH.isSideAt());
        assertTrue(ScanOffset.NORTH_U.isSideUp());
        assertTrue(ScanOffset.NORTH_D.isSideDown());
        assertTrue(ScanOffset.NORTH_D_2.isSideDown2());

        // The diagonals belong to none of the four groups, so the lava-escape logic in
        // blockBreakEffect never re-pitches for them.
        assertFalse(ScanOffset.NORTHWEST_D.isSideAt());
        assertFalse(ScanOffset.NORTHWEST_D.isSideUp());
        assertFalse(ScanOffset.NORTHWEST_D.isSideDown());
        assertFalse(ScanOffset.NORTHWEST_D.isSideDown2());
    }

    @Test
    void sideUpAndSideDownWalkTheHeightLadder() {
        // The ladder is D_2 -> D -> at -> U. blockBreakEffect walks it when a bot standing over
        // lava has to retarget mid-break.
        assertEquals(ScanOffset.NORTH_D, ScanOffset.NORTH_D_2.sideUp());
        assertEquals(ScanOffset.NORTH, ScanOffset.NORTH_D.sideUp());
        assertEquals(ScanOffset.NORTH_U, ScanOffset.NORTH.sideUp());
        assertNull(ScanOffset.NORTH_U.sideUp(), "the top of the ladder has no rung above");

        assertEquals(ScanOffset.NORTH, ScanOffset.NORTH_U.sideDown());
        assertEquals(ScanOffset.NORTH_D, ScanOffset.NORTH.sideDown());
        assertEquals(ScanOffset.NORTH_D_2, ScanOffset.NORTH_D.sideDown());
        assertNull(ScanOffset.NORTH_D_2.sideDown(), "the bottom of the ladder has no rung below");
    }

    @Test
    void theLadderIsSymmetricForEveryDirection() {
        // Upstream wrote sideUp and sideDown as two 12-case switches. This is the invariant
        // that makes them a pair, and it catches a single transposed case.
        for (ScanOffset offset : ScanOffset.values()) {
            ScanOffset up = offset.sideUp();

            if (up != null) {
                assertEquals(offset, up.sideDown(), offset + ".sideUp().sideDown() must round-trip");
            }
        }
    }

    @Test
    void theWrapperIsMutable() {
        // Mining mutates the level mid-break when a bot over lava changes what it is attacking,
        // and the wrapper is how that change gets back to the caller.
        ScanOffset.Wrapper wrapper = new ScanOffset.Wrapper(ScanOffset.NORTH);
        wrapper.set(ScanOffset.NORTH_D);

        assertEquals(ScanOffset.NORTH_D, wrapper.get());
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.agent.legacy.ScanOffsetTest'
```

Expected: compile failure, `cannot find symbol: class ScanOffset`.

- [ ] **Step 3: Write `ScanOffset`**

Copy `LegacyLevel` across with three changes: the name, `Vec3i` offsets instead of three ints, and
`apply(BlockPos)` returning a new position instead of `offset(Location)` mutating one. The five
predicate sets, the two ladder switches and `getOffset` are verbatim.

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * A direction and height a bot can attack a block at, relative to itself.
 *
 * <p>Ported from {@code LegacyLevel}. The name changes because "level" means a world in vanilla;
 * these are scan offsets, which is what {@code SurroundingScan} uses them for.
 *
 * <p>The constants encode two things at once: where the block is, and what pitch the bot should
 * hold while breaking it. {@code Mining.preBreak} reads the {@code isSideDown}/{@code isSideUp}
 * groups to pick 69 or -53 degrees.
 */
public enum ScanOffset {
    ABOVE(0, 2, 0),
    BELOW(0, -1, 0),
    AT(0, 1, 0),
    AT_D(0, 0, 0),
    NORTH_U(0, 2, -1),
    SOUTH_U(0, 2, 1),
    EAST_U(1, 2, 0),
    WEST_U(-1, 2, 0),
    NORTH(0, 1, -1),
    SOUTH(0, 1, 1),
    EAST(1, 1, 0),
    WEST(-1, 1, 0),
    NORTH_D(0, 0, -1),
    SOUTH_D(0, 0, 1),
    EAST_D(1, 0, 0),
    WEST_D(-1, 0, 0),
    NORTHWEST_D(-1, 0, -1),
    SOUTHWEST_D(-1, 0, 1),
    NORTHEAST_D(1, 0, -1),
    SOUTHEAST_D(1, 0, 1),
    NORTH_D_2(0, -1, -1),
    SOUTH_D_2(0, -1, 1),
    EAST_D_2(1, -1, 0),
    WEST_D_2(-1, -1, 0);

    /** The four that are not directional. {@link #isSide()} is the negation of this. */
    private static final Set<ScanOffset> NON_SIDE = Set.of(ABOVE, BELOW, AT, AT_D);

    private static final Set<ScanOffset> SIDE_AT = Set.of(NORTH, SOUTH, EAST, WEST);
    private static final Set<ScanOffset> SIDE_UP = Set.of(NORTH_U, SOUTH_U, EAST_U, WEST_U);
    private static final Set<ScanOffset> SIDE_DOWN = Set.of(NORTH_D, SOUTH_D, EAST_D, WEST_D);
    private static final Set<ScanOffset> SIDE_DOWN_2 = Set.of(NORTH_D_2, SOUTH_D_2, EAST_D_2, WEST_D_2);

    private final Vec3i offset;

    ScanOffset(int x, int y, int z) {
        this.offset = new Vec3i(x, y, z);
    }

    /**
     * {@code pos} displaced by this offset.
     *
     * <p>Upstream's {@code offset(Location)} mutated its argument, because Bukkit's
     * {@code Location.add} does. {@code BlockPos} is immutable, so this returns a new position
     * and no caller has to clone defensively — which several upstream callers forgot to do.
     */
    public BlockPos apply(BlockPos pos) {
        return pos.offset(offset);
    }

    /** True for every constant except the four in {@link #NON_SIDE}. */
    public boolean isSide() {
        return !NON_SIDE.contains(this);
    }

    public boolean isSideAt() {
        return SIDE_AT.contains(this);
    }

    public boolean isSideUp() {
        return SIDE_UP.contains(this);
    }

    public boolean isSideDown() {
        return SIDE_DOWN.contains(this);
    }

    public boolean isSideDown2() {
        return SIDE_DOWN_2.contains(this);
    }

    /** One rung up the height ladder D_2 -> D -> at -> U, or null at the top. */
    public @Nullable ScanOffset sideUp() {
        return switch (this) {
            case NORTH -> NORTH_U;
            case SOUTH -> SOUTH_U;
            case EAST -> EAST_U;
            case WEST -> WEST_U;
            case NORTH_D -> NORTH;
            case SOUTH_D -> SOUTH;
            case EAST_D -> EAST;
            case WEST_D -> WEST;
            case NORTH_D_2 -> NORTH_D;
            case SOUTH_D_2 -> SOUTH_D;
            case EAST_D_2 -> EAST_D;
            case WEST_D_2 -> WEST_D;
            default -> null;
        };
    }

    /** One rung down the height ladder, or null at the bottom. */
    public @Nullable ScanOffset sideDown() {
        return switch (this) {
            case NORTH_U -> NORTH;
            case SOUTH_U -> SOUTH;
            case EAST_U -> EAST;
            case WEST_U -> WEST;
            case NORTH -> NORTH_D;
            case SOUTH -> SOUTH_D;
            case EAST -> EAST_D;
            case WEST -> WEST_D;
            case NORTH_D -> NORTH_D_2;
            case SOUTH_D -> SOUTH_D_2;
            case EAST_D -> EAST_D_2;
            case WEST_D -> WEST_D_2;
            default -> null;
        };
    }

    /** The constant matching the displacement from {@code start} to {@code end}, or null. */
    public static @Nullable ScanOffset getOffset(BlockPos start, BlockPos end) {
        int diffX = end.getX() - start.getX();
        int diffY = end.getY() - start.getY();
        int diffZ = end.getZ() - start.getZ();

        for (ScanOffset offset : values()) {
            if (offset.offset.getX() == diffX && offset.offset.getY() == diffY
                    && offset.offset.getZ() == diffZ) {
                return offset;
            }
        }

        return null;
    }

    /**
     * A mutable holder, ported from {@code LegacyLevel.LevelWrapper}.
     *
     * <p>{@code Mining.blockBreakEffect} runs as a repeating task and can decide part-way
     * through a break that it is attacking a different block — a bot suspended over lava
     * retargets up or down. The wrapper is how that decision survives between ticks of the
     * same task.
     */
    public static final class Wrapper {

        private @Nullable ScanOffset offset;

        public Wrapper(@Nullable ScanOffset offset) {
            this.offset = offset;
        }

        public @Nullable ScanOffset get() {
            return offset;
        }

        public void set(@Nullable ScanOffset offset) {
            this.offset = offset;
        }
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.agent.legacy.ScanOffsetTest'
```

Expected: 8 tests, 0 failures. `theLadderIsSymmetricForEveryDirection` is the one that earns its
keep — it catches a single transposed case in either 12-arm switch.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy/ScanOffset.java src/test/java/net/nuggetmc/tplus/agent/legacy/ScanOffsetTest.java
git commit -m "feat: add the ScanOffset enum"
```

---
# Phase 6: Mining

A bot that cannot break blocks gets stuck on the first fence. This phase adds the mining pipeline
and the two vertical navigation checks that drive it.

The pipeline has three layers, and they are worth holding in mind before reading any of them:

1. `preBreak` picks a tool, points the bot at the block, and starts a **swing animation** task —
   one swing every 4 ticks, forever, until something cancels it.
2. `blockBreakEffect` starts a second, independent **progress** task — one stage every 2 ticks,
   ten stages, then the block breaks. It sends the crack-overlay packet clients draw.
3. Everything else calls `preBreak` and lets those two run.

The two tasks are separate on purpose upstream, and they cancel on different conditions: the
animation stops when the bot stops mining, the progress stops when the target block changes.

## Task 16: `TickScheduler.runRepeating`, `Mining.preBreak`, and the three `checkAt` variants

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/util/TickScheduler.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/Mining.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockRules.java` — the three predicates
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` — insert checks 10-12
- Test: `src/test/java/net/nuggetmc/tplus/util/TickSchedulerTest.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java`

Read the originals:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '985,1070p'
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '980,1010p'
```

- [ ] **Step 1: Write the failing `runRepeating` tests**

Append to `src/test/java/net/nuggetmc/tplus/util/TickSchedulerTest.java`:

```java
    @Test
    void aRepeatingTaskRunsEveryPeriod() {
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        scheduler.runRepeating(2, runs::incrementAndGet);

        // Upstream's runTaskTimer(plugin, 0, 2) fires immediately and then every 2 ticks.
        // Ours cannot fire inline — nothing in TickScheduler ever does, by design — so the
        // first run is the next tick. Mining's swing cadence is the only thing that notices
        // and one tick of swing delay is invisible.
        for (int i = 0; i < 6; i++) {
            scheduler.tick();
        }

        assertEquals(3, runs.get(), "ticks 2, 4 and 6");
    }

    @Test
    void aCancelledRepeatingTaskStops() {
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        int id = scheduler.runRepeating(1, runs::incrementAndGet);

        scheduler.tick();
        scheduler.tick();
        scheduler.cancel(id);
        scheduler.tick();
        scheduler.tick();

        assertEquals(2, runs.get(), "no runs after cancellation");
    }

    @Test
    void aRepeatingTaskThatThrowsKeepsRunning() {
        // Mining's swing task touches the world every 4 ticks forever. One bad tick must not
        // silently end the animation, or a bot mines invisibly.
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        scheduler.runRepeating(1, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        scheduler.tick();
        scheduler.tick();

        assertEquals(2, runs.get(), "a throwing repeating task must be rescheduled");
    }

    @Test
    void cancelAllStopsRepeatingTasksToo() {
        TickScheduler scheduler = new TickScheduler();
        AtomicInteger runs = new AtomicInteger();

        scheduler.runRepeating(1, runs::incrementAndGet);
        scheduler.tick();
        scheduler.cancelAll();
        scheduler.tick();
        scheduler.tick();

        assertEquals(1, runs.get());
    }
```

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew test --tests 'net.nuggetmc.tplus.util.TickSchedulerTest'
```

Expected: compile failure, `cannot find symbol: method runRepeating`.

- [ ] **Step 3: Add `runRepeating`**

The existing `runLater` and `tick` stay exactly as they are. Repetition is expressed as a task that
reschedules itself, which reuses the cancellation set and the per-task error isolation rather than
adding a second mechanism:

```java
    /**
     * Schedules {@code action} to run every {@code periodTicks}, starting one period from now.
     *
     * <p>Replaces {@code BukkitRunnable.runTaskTimer(plugin, 0, period)}. Two differences, both
     * deliberate: the first run is one period away rather than immediate, because nothing in
     * this class runs inline; and the id stays stable across repeats, so a single
     * {@link #cancel(int)} stops the task for good.
     *
     * <p>A throwing run is logged by {@link #tick()} and the task is still rescheduled — the
     * swing animation in {@code Mining} must survive one bad tick.
     *
     * @return an id usable with {@link #cancel(int)}
     */
    public int runRepeating(long periodTicks, Runnable action) {
        int id = nextId++;
        schedule(id, Math.max(1, periodTicks), action);
        return id;
    }

    private void schedule(int id, long period, Runnable action) {
        long due = currentTick + period;

        queue.computeIfAbsent(due, k -> new ArrayList<>()).add(new Task(id, () -> {
            try {
                action.run();
            } finally {
                // In a finally block so a throwing action does not end the repetition. The
                // cancellation check in tick() is what stops it; re-arming a cancelled id is
                // harmless because it will be skipped and the id stays in `cancelled`.
                if (!cancelled.contains(id)) {
                    schedule(id, period, action);
                }
            }
        }));
    }
```

`cancel` currently does `cancelled.add(id)` and `tick` does `cancelled.remove(task.id())` — a
one-shot semantic. That removal would let a repeating task resume after one skipped run, so
`tick` needs to stop consuming the flag:

```java
        for (Task task : due) {
            if (cancelled.contains(task.id())) {
                continue;
            }
            // The try/catch body below is unchanged.
            try {
                task.action().run();
            } catch (Throwable t) {
                LOGGER.error("Scheduled TerminatorPlus task {} failed", task.id(), t);
            }
        }
```

The set now grows without bound over a long session. Bound it in `cancelAll` (already clears it)
and by pruning in `cancel` when the id has no queued task — or simply leave it: the ids are `int`s,
a busy session cancels a few thousand, and `cancelAll` runs on every reset. **Leave it, and say so
here** rather than building a reaper that is never exercised.

Re-run the existing `TickScheduler` tests as well as the new ones: changing `cancelled` from
consume-on-read to persistent affects `runLater` too, and Plan A has tests pinning that behaviour.

```bash
./gradlew test --tests 'net.nuggetmc.tplus.util.TickSchedulerTest'
```

Expected: all tests pass, new and old. If a Plan A test asserted that cancelling one scheduled task
does not affect a later task **reusing the same id**, that can no longer happen — ids are never
reused — so update the test's comment rather than the code.

- [ ] **Step 4: Add the three obstacle predicates to `BlockRules`**

These are `LegacyAgent.checkAt`, `checkFenceAndGates`, `checkObstacles` and `isDoorObstacle`. Spec
§4.3 puts them in `BlockRules`; they are predicates, and the `preBreak` call that follows each one
belongs to the caller.

```java
    /**
     * Whether a bot must break the block at {@code state} to pass through the space it occupies.
     *
     * <p>Ported from {@code LegacyAgent.checkAt}, inverted: upstream returned true for "handled,
     * I am now mining it", which is a decision for the caller. This returns whether the block is
     * in the way.
     */
    public static boolean blocksPath(BlockState state) {
        return !isBreak(state);
    }

    /** Ported from {@code checkFenceAndGates}. */
    public static boolean isFenceOrGate(BlockState state) {
        return isFence(state) || isGate(state);
    }

    /**
     * Ported from {@code checkObstacles} plus {@code isDoorObstacle}.
     *
     * <p>The door rule is upstream's and is asymmetric: **any** door is an obstacle, but a
     * trapdoor only when it is open. A closed trapdoor is floor.
     */
    public static boolean isObstacleOrDoor(BlockState state) {
        if (isObstacle(state)) {
            return true;
        }

        if (state.is(BlockTags.DOORS)) {
            return true;
        }

        return state.is(BlockTags.TRAPDOORS)
                && state.getValueOrElse(BlockStateProperties.OPEN, false);
    }
```

Add the import `net.minecraft.world.level.block.state.properties.BlockStateProperties`.

- [ ] **Step 5: Write `Mining.preBreak` and the swing animation**

`blockBreakEffect` is Task 17; this task ends with `preBreak` calling a stub for it so the three
`checkAt` variants can be wired and seen to work.

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;

import java.util.List;

/**
 * Breaking blocks: tool choice, the swing animation, the crack overlay, and the break itself.
 *
 * <p>Ported from {@code LegacyAgent.preBreak}, {@code blockBreakEffect}, {@code downMine},
 * {@code stopMining} and {@code placeWaterDown}.
 *
 * <p>Two independent repeating tasks per mined block, which is upstream's design: a swing
 * animation every 4 ticks keyed on the **bot**, and a progress counter every 2 ticks keyed on the
 * **block**. They start together and stop for different reasons.
 */
public final class Mining {

    /**
     * The tools a bot will consider. Upstream's {@code LegacyItems}: one iron tool of each kind,
     * so a bot never holds a diamond pickaxe it did not earn.
     */
    private static final List<ItemStack> TOOLS = List.of(
            new ItemStack(Items.IRON_PICKAXE),
            new ItemStack(Items.IRON_AXE),
            new ItemStack(Items.IRON_SHOVEL));

    /** Pitch while breaking a block one level below eye height. Upstream's magic number. */
    private static final float PITCH_DOWN = 69f;

    /** Pitch while breaking a block above. */
    private static final float PITCH_UP = -53f;

    private final AgentState state;
    private final Agent agent;

    public Mining(AgentState state, Agent agent) {
        this.state = state;
        this.agent = agent;
    }

    /**
     * Starts the bot mining the block at {@code pos}.
     *
     * <p>Ported from {@code preBreak}. Picks the fastest of the three tools, aims the bot
     * according to {@code offset}, starts the swing animation if one is not already running, and
     * hands off to {@link #blockBreakEffect}.
     *
     * <p>The aiming is the part that looks arbitrary and is not: a block at foot level needs a
     * steep downward pitch to be in reach, and {@code btCheck} is forced true five ticks later so
     * that {@code checkUp} and {@code checkSide} unlock while the bot is committed to this block.
     */
    public void preBreak(Bot bot, BlockPos pos, ScanOffset offset) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockState target = level.getBlockState(pos);

        bot.setItem(optimalTool(target));

        if (offset.isSideDown() || offset.isSideDown2()) {
            bot.setBotPitch(PITCH_DOWN);

            // Upstream forces the same-column flag on five ticks in. It is not a measurement —
            // it is a way of telling the rest of tickBot "this bot is committed, let checkUp
            // and checkSide run". Faithfully odd.
            agent.later(5, () -> state.btCheck.put(bot, true));
        } else if (offset.isSideUp()) {
            bot.setBotPitch(PITCH_UP);
        } else if (offset == ScanOffset.AT_D || offset == ScanOffset.AT) {
            // Aims a block below the target's centre, which points the bot's head at the
            // block's lower face rather than through it.
            bot.faceLocation(Vec3.atCenterOf(pos).add(0, -1, 0));
        }

        if (!state.miningAnim.containsKey(bot)) {
            int id = agent.repeating(4, bot::punch);
            state.miningAnim.put(bot, id);
        }

        blockBreakEffect(bot, pos, new ScanOffset.Wrapper(offset));
    }

    /** Task 17. */
    void blockBreakEffect(Bot bot, BlockPos pos, ScanOffset.Wrapper wrapper) {
    }

    /**
     * The fastest of the three tools against {@code target}, or an empty hand.
     *
     * <p>Ported from {@code preBreak}'s tool loop. Upstream compared Bukkit's
     * {@code Block.getDestroySpeed(tool)} against a starting value of 1, so a block no tool helps
     * with leaves the bot bare-handed. {@code ItemStack.getDestroySpeed(BlockState)} is the
     * vanilla equivalent and is the tool's multiplier for that block, same orientation.
     */
    private static ItemStack optimalTool(BlockState target) {
        ItemStack optimal = ItemStack.EMPTY;
        float optimalSpeed = 1;

        for (ItemStack tool : TOOLS) {
            float speed = tool.getDestroySpeed(target);

            if (speed > optimalSpeed) {
                optimal = tool;
                optimalSpeed = speed;
            }
        }

        return optimal;
    }
}
```

`agent.repeating(period, action)` is the repeating twin of `Agent.later`; add it next to that
method so the id lands in `taskList` and `stopAllTasks` reaches it:

```java
    /** Schedules a repeating {@code action} and remembers the id. */
    protected int repeating(long periodTicks, Runnable action) {
        if (registry == null) {
            return -1;
        }

        int id = registry.scheduler().runRepeating(periodTicks, action);
        taskList.add(id);
        return id;
    }
```

`later`, `cancel` and `random` are already public for this reason (Task 7); keep `repeating` public
to match. A separate `AgentTasks` interface between classes that ship together would be ceremony.

- [ ] **Step 6: Wire checks 10, 11 and 12 into `tickBot`**

Exactly where the Task 12 comment says, in this order:

```java
            BlockPos botPos = BlockPos.containing(pos);
            BlockPos headPos = botPos.above();

            if (BlockRules.blocksPath(level.getBlockState(headPos))) {
                mining.preBreak(bot, headPos, ScanOffset.AT);
                return;
            }

            if (BlockRules.isFenceOrGate(level.getBlockState(botPos))) {
                mining.preBreak(bot, botPos, ScanOffset.AT_D);
                return;
            }

            if (BlockRules.isObstacleOrDoor(level.getBlockState(botPos))) {
                mining.preBreak(bot, botPos, ScanOffset.AT_D);
                return;
            }
```

Upstream computed `Block block = loc.clone().add(0, 1, 0).getBlock()` before the XZ comparison and
used it for `checkAt`, then used `loc.getBlock()` for the other two. The order — head block first,
then foot block twice — matters: a bot inside a fence with a solid block above it mines upward
first.

Construct `Mining` in `LegacyAgent`'s constructor after `navigation`:

```java
        this.mining = new Mining(state, this);
```

- [ ] **Step 7: Write the failing behaviour GameTests**

Append to `AgentTests`:

```java
    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_bot_breaks_a_wall_between_it_and_its_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(6, 1, 7));

        // A 1x2 stone wall directly east of the bot. Upstream's checkAt fires on the head
        // block, so the bot mines the upper one first.
        helper.setBlock(new BlockPos(7, 1, 7), Blocks.STONE);
        helper.setBlock(new BlockPos(7, 2, 7), Blocks.STONE);

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(9.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 200);

        helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(7, 2, 7));

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_bot_breaks_a_fence_it_is_standing_in(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();

        helper.setBlock(new BlockPos(7, 1, 7), Blocks.OAK_FENCE);
        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(11.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 200);

        helper.assertBlockNotPresent(Blocks.OAK_FENCE, new BlockPos(7, 1, 7));

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_bot_breaks_a_cobweb_obstacle(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();

        helper.setBlock(new BlockPos(7, 1, 7), Blocks.COBWEB);
        Bot bot = spawn(helper, registry, new BlockPos(7, 1, 7));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(11.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 200);

        helper.assertBlockNotPresent(Blocks.COBWEB, new BlockPos(7, 1, 7));

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void mining_starts_exactly_one_swing_animation_per_bot(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(6, 1, 7));

        helper.setBlock(new BlockPos(7, 2, 7), Blocks.OBSIDIAN);

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(9.5, 1, 7.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 60);

        // preBreak runs every tick while the bot is blocked, and the guard is the only thing
        // stopping it stacking one swing task per tick. Obsidian is used so the block does not
        // break and end the test early.
        helper.assertTrue(registry.state().miningAnim.containsKey(bot), "an animation must be running");
        helper.assertValueEqual(registry.state().miningAnim.size(), 1, "exactly one, not sixty");

        registry.reset();
        helper.succeed();
    }
```

These will not pass until Task 17 supplies `blockBreakEffect` — the swing animation runs but nothing
ever breaks. Run them at the end of Task 17; the last one (`mining_starts_exactly_one_swing`) does
pass now and is worth running immediately.

- [ ] **Step 8: Build and commit**

```bash
./gradlew build
git add src/main/java/net/nuggetmc/tplus src/test/java/net/nuggetmc/tplus/util
git commit -m "feat: add repeating tasks, tool choice and obstacle detection"
```

---

## Task 17: `Mining.blockBreakEffect`

117 lines, and the densest method in the port. It is a repeating task that re-derives its own
target every 2 ticks, re-aims the bot when it is suspended over lava, sends the crack overlay,
and breaks the block at stage 9. Upstream's own comment partway through reads "wow this repeated
code is so bad lmao".

Read it twice before writing:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '1070,1190p'
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Mining.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/BotFactory.java` — the crack packet
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java` (already written)

- [ ] **Step 1: Add the crack packet to `BotFactory`**

Upstream sent this through `TerminatorPlusAPI.getInternalBridge().sendBlockDestructionPacket(id,
block, stage)` — the bridge indirection spec §4.4 deletes.

```java
    /**
     * Sends the block-crack overlay to every real player.
     *
     * <p>{@code id} is an arbitrary per-block animation id so two bots cracking two blocks do not
     * overwrite each other; {@code stage} runs 0 to 9, and -1 clears the overlay.
     *
     * <p>Replaces the Paper build's {@code InternalBridgeImpl.sendBlockDestructionPacket}.
     */
    public static void broadcastCrack(Bot source, int id, BlockPos pos, int stage) {
        broadcast(source, new ClientboundBlockDestructionPacket(id, pos, stage));
    }
```

- [ ] **Step 2: Write `blockBreakEffect`**

```java
    /**
     * Starts the progress counter that eventually breaks the block at {@code pos}.
     *
     * <p>Ported from {@code blockBreakEffect}. One task per block, ticking every 2 ticks through
     * ten stages. Three things make it more than a counter:
     *
     * <ul>
     * <li>It re-derives its target from the bot's <em>current</em> position each tick, through
     *     {@code wrapper}'s offset, and cancels if that no longer matches the block it started
     *     on. That is how mining stops when the bot moves.
     * <li>If the bot is suspended over lava it re-aims one rung up or down the {@link ScanOffset}
     *     ladder and keeps going, mutating the wrapper so the change persists. Upstream's comment:
     *     "Fix boat clutching while breaking block. As a side effect, the bot is able to break
     *     multiple blocks at once while over lava."
     * <li>Six block types are hard-refused at the last moment — bedrock, barrier, command blocks,
     *     the end portal frame, the structure block. It returns without advancing, so the task
     *     spins forever on them rather than stopping. Faithfully wasteful.
     * </ul>
     */
    void blockBreakEffect(Bot bot, BlockPos pos, ScanOffset.Wrapper wrapper) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockState started = level.getBlockState(pos);

        if (BlockRules.isNoCrack(started)) {
            return;
        }

        AgentState.BlockRef ref = new AgentState.BlockRef(level.dimension(), pos);

        if (state.crackList.containsKey(ref)) {
            return;
        }

        state.crackList.put(ref, (short) agent.random().nextInt(2000));

        int[] taskId = new int[1];
        taskId[0] = agent.repeating(2, () -> {
            byte stage = state.mining.getOrDefault(taskId[0], (byte) 0);

            BlockPos current = currentTarget(bot, wrapper);
            current = adjustForLava(bot, level, pos, current, wrapper);

            BlockState currentState = current == null ? null : level.getBlockState(current);

            // Upstream compared both the position and the block type: the bot has to still be
            // aiming at this block, and it has to still be the same kind of block.
            if (!bot.isBotAlive() || current == null || !pos.equals(current)
                    || currentState.getBlock() != level.getBlockState(pos).getBlock()) {
                finish(bot, ref, taskId[0], pos);
                return;
            }

            SoundEvent sound = LegacyUtils.breakBlockSound(level.getBlockState(pos));

            if (stage == 9) {
                if (sound != null) {
                    level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f);
                }

                level.destroyBlock(pos, true, bot);

                if (wrapper.get() == ScanOffset.ABOVE) {
                    // Breaking the block overhead then jumping into the hole is how a bot gets
                    // itself stuck, so jumping is suppressed for 15 ticks.
                    state.noJump.add(bot);
                    agent.later(15, () -> state.noJump.remove(bot));
                }

                finish(bot, ref, taskId[0], pos);
                return;
            }

            if (sound != null) {
                level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.3f, 1f);
            }

            if (UNBREAKABLE.contains(level.getBlockState(pos).getBlock())) {
                return;
            }

            if (BlockRules.isInstantBreak(level.getBlockState(pos))) {
                level.destroyBlock(pos, true, bot);
                return;
            }

            BotFactory.broadcastCrack(bot, state.crackList.get(ref), pos, stage);
            state.mining.put(taskId[0], (byte) (stage + 1));
        });

        state.mining.put(taskId[0], (byte) 0);
    }

    /** Clears the overlay and forgets the block. */
    private void finish(Bot bot, AgentState.BlockRef ref, int taskId, BlockPos pos) {
        Short id = state.crackList.remove(ref);

        if (id != null) {
            BotFactory.broadcastCrack(bot, id, pos, -1);
        }

        state.mining.remove(taskId);
        agent.cancel(taskId);
    }

    /** Where the bot is currently aiming, per the wrapper's offset. */
    private BlockPos currentTarget(Bot bot, ScanOffset.Wrapper wrapper) {
        ScanOffset offset = wrapper.get();

        if (offset == null) {
            return BlockPos.containing(bot.position()).above();
        }

        if (offset == ScanOffset.BELOW) {
            List<BlockPos> standing = bot.getStandingOn();
            return standing.isEmpty() ? null : standing.get(0);
        }

        return offset.apply(BlockPos.containing(bot.position()));
    }

    /**
     * Re-aims a bot suspended over lava, and reports where it is now aiming.
     *
     * <p>Ported from the two near-identical blocks in the middle of upstream's
     * {@code blockBreakEffect}. One handles lava two blocks below with the bot aiming one above
     * the target, the other lava one block below with the bot aiming one below. Both walk the
     * {@link ScanOffset} ladder and re-pitch.
     */
    private BlockPos adjustForLava(Bot bot, ServerLevel level, BlockPos block,
                                   BlockPos current, ScanOffset.Wrapper wrapper) {
        ScanOffset offset = wrapper.get();

        if (offset == null || current == null) {
            return current;
        }

        BlockPos botPos = BlockPos.containing(bot.position());

        if ((offset.isSideAt() || offset.isSideUp())
                && level.getBlockState(botPos.below(2)).getBlock() == Blocks.LAVA
                && block.above().equals(current)) {
            wrapper.set(offset.sideDown());
            repitch(bot, wrapper);
            return block;
        }

        if ((offset.isSideAt() || offset.isSideDown())
                && level.getBlockState(botPos.below()).getBlock() == Blocks.LAVA
                && block.below().equals(current)) {
            wrapper.set(offset.sideUp());
            repitch(bot, wrapper);
            return block;
        }

        return current;
    }

    private void repitch(Bot bot, ScanOffset.Wrapper wrapper) {
        ScanOffset offset = wrapper.get();

        if (offset == null) {
            return;
        }

        if (offset.isSideDown() || offset.isSideDown2()) {
            bot.setBotPitch(PITCH_DOWN);
        } else if (offset.isSideUp()) {
            bot.setBotPitch(PITCH_UP);
        } else if (offset.isSide()) {
            bot.setBotPitch(0);
        }
    }

    /**
     * Blocks upstream refused to break, checked at the last moment before advancing a stage.
     *
     * <p>Upstream listed {@code STRUCTURE_BLOCK} twice. Deduplicated by the set, no behaviour
     * change.
     */
    private static final Set<Block> UNBREAKABLE = Set.of(
            Blocks.BARRIER, Blocks.BEDROCK,
            Blocks.END_PORTAL_FRAME, Blocks.STRUCTURE_BLOCK,
            Blocks.COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK);
```

`Agent.random()` and `Agent.cancel(int)` already exist — Task 7 added them alongside `later`.

**The `int[] taskId` trick needs a comment in the code**, because it looks like a mistake. The task
needs its own id to look up its progress, and the id is only known after `repeating` returns — a
one-element array is the standard way out of that in Java. Upstream had the same problem and solved
it by keying `mining` on the `BukkitRunnable` itself, which was available as `this` inside the
anonymous class.

- [ ] **Step 3: Run the mining GameTests**

```bash
./gradlew runGameTestServer
```

Expected: `All 82 required tests passed :)`, including the four from Task 16.

If `a_bot_breaks_a_wall_between_it_and_its_target` times out, add a temporary log line inside the
repeating task printing `stage` and `current` — the likely failure is `currentTarget` disagreeing
with the position `preBreak` was called with, which cancels the task on its first run and mines
nothing, forever, silently.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus
git commit -m "feat: add the block-break progress task and crack overlay"
```

---

## Task 18: `downMine`, `stopMining` and `placeWaterDown`

The three small members of `Mining`. `downMine` is the nudge that centres a bot on the block it is
about to mine out from under itself; `placeWaterDown` is the fire-extinguishing routine
`BotBehaviors` will call in Task 22.

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '444,465p;944,985p;1190,1215p'
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Mining.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` — check 4

- [ ] **Step 1: Write the three methods**

```java
    /**
     * Centres the bot on its own block before it mines the floor away.
     *
     * <p>Ported from {@code downMine}. Two independent nudges, and the second one is not an
     * else-branch: a bot in water gets both.
     *
     * <p>The arithmetic looks like a no-op and upstream's was: it takes the bot's position,
     * replaces X and Z with the block-centre values, then subtracts the bot's position — which
     * gives the offset from the bot to its block's centre. Upstream's version used the same
     * {@code Location} object for both halves and called {@code setX} on it, so the subtraction
     * was against the already-modified value and always produced 0.5 in each axis. Ours takes a
     * copy first, which changes the numbers.
     */
    public void downMine(Bot bot, BlockPos block) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        Vec3 centre = new Vec3(Math.floor(pos.x) + 0.5, pos.y, Math.floor(pos.z) + 0.5);

        if (!BlockRules.isNoCrack(level.getBlockState(block))) {
            MotionVec nudge = MotionVec.of(centre.subtract(pos));

            if (nudge.length() > 1) {
                nudge.normalize();
            }

            nudge.setY(0).multiply(0.1);
            bot.setVelocity(nudge);
        }

        if (bot.isBotInWater()) {
            MotionVec dive = MotionVec.of(centre.subtract(pos));

            if (dive.length() > 1) {
                dive.normalize();
            }

            dive.multiply(0.3).setY(-1);

            if (!state.fallDamageCooldown.contains(bot)) {
                state.fallDamageCooldown.add(bot);
                agent.later(10, () -> state.fallDamageCooldown.remove(bot));
            }

            bot.setVelocity(dive);
        }
    }

    /** Stops a bot's swing animation. Ported from {@code stopMining}. */
    public void stopMining(Bot bot) {
        Integer task = state.miningAnim.remove(bot);

        if (task != null) {
            agent.cancel(task);
        }
    }

    /**
     * Dumps a water bucket at {@code pos} and picks it back up five ticks later.
     *
     * <p>Ported from {@code placeWaterDown}. Used by {@code BotBehaviors} to put out a fire, or
     * to cool lava the bot is standing in. Unlike the MLG in {@code onFallDamage} this has no
     * waterlogging branch — upstream did not add one here, and the call sites are all air or
     * fire.
     */
    public void placeWaterDown(Bot bot, BlockPos pos) {
        ServerLevel level = (ServerLevel) bot.level();

        if (level.getBlockState(pos).getBlock() == Blocks.WATER) {
            return;
        }

        bot.look(Direction.DOWN);
        bot.punch();
        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1f, 1f);
        bot.setItem(new ItemStack(Items.BUCKET));

        agent.later(5, () -> {
            if (level.getBlockState(pos).getBlock() != Blocks.WATER) {
                return;
            }

            bot.look(Direction.DOWN);
            bot.setItem(new ItemStack(Items.WATER_BUCKET));
            level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1f, 1f);
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        });
    }
```

**The `downMine` deviation needs a decision, not a silent choice.** Upstream's code is:

```java
Location locBlock = player.getLocation();
locBlock.setX(locBlock.getBlockX() + 0.5);
locBlock.setZ(locBlock.getBlockZ() + 0.5);
Vector vector = locBlock.toVector().subtract(player.getLocation().toVector());
```

`player.getLocation()` returns a **fresh** `Location` each call in Bukkit, so `locBlock` and the
second `getLocation()` are different objects and the subtraction is real — the version above is
correct, not a fix. Confirm that by reading Bukkit's `getLocation` contract before accepting either
reading; if it did return the same object the nudge would always be `(0.5, 0, 0.5)` normalised,
and the comment above needs deleting.

- [ ] **Step 2: Wire check 4 into `tickBot`**

Replace the Task 12 placeholder:

```java
        if (livingTarget == null) {
            mining.stopMining(bot);
            return;
        }
```

- [ ] **Step 3: Build and commit**

```bash
./gradlew build && ./gradlew runGameTestServer
git add src/main/java/net/nuggetmc/tplus/agent/legacy
git commit -m "feat: add downMine, stopMining and placeWaterDown"
```

---

## Task 19: `Navigation.checkDown` and `checkUp`

The two vertical navigation checks. `checkDown` digs toward a target below; `checkUp` towers toward
one above — the pillar-jumping behaviour that makes the bots recognisable.

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '766,944p'
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java`
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockScan.java` — `placeBlock` only
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` — checks 13 and 14
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java`

`checkUp` calls `blockCheck.placeBlock`, so `BlockScan` starts here with that one method. Its other
three (`placeFinal` is its helper, `tryPreMLG` and `clutch`) land in Task 23.

- [ ] **Step 1: Write the failing GameTests**

```java
    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "9x30x9", floor = true)
    static void a_bot_towers_toward_a_target_above_it(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(4, 1, 4));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(4.5, 8, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        double startY = bot.getY();
        run(registry, bot, 300);

        // checkUp needs withinTargetXZ (same column, which this is) and three clear blocks
        // above the bot. It then places cobblestone at the bot's feet and jumps.
        helper.assertTrue(bot.getY() > startY + 1.0,
                "the bot must climb; y went " + startY + " -> " + bot.getY());

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "9x30x9", floor = true)
    static void a_bot_mines_down_toward_a_target_far_below(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();

        // A solid column of stone under the bot, and the target 12 blocks down beside it.
        for (int y = 2; y <= 14; y++) {
            for (int x = 3; x <= 5; x++) {
                for (int z = 3; z <= 5; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }

        Bot bot = spawn(helper, registry, new BlockPos(4, 15, 4));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(4.5, 2, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        double startY = bot.getY();
        run(registry, bot, 400);

        // checkDown needs no line of sight to the target and a height difference of more than
        // 10 with a horizontal distance under 10 — or sameXZ and a difference of more than 1.
        helper.assertTrue(bot.getY() < startY - 1.0,
                "the bot must dig down; y went " + startY + " -> " + bot.getY());

        registry.reset();
        helper.succeed();
    }
```

- [ ] **Step 2: Write `BlockScan.placeBlock`**

This is the "place a block, and shore up whatever it needs to rest on" routine — 90 lines of
neighbour tests that decide whether to place one block or two, and in which order.

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyBlockCheck.java | sed -n '33,145p'
```

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;

import java.util.List;

/**
 * Placing blocks: towering, and the two clutch routines.
 *
 * <p>Ported from {@code LegacyBlockCheck}, which was a real collaborator of {@code LegacyAgent}
 * constructed with {@code (LegacyAgent, Plugin)} and held 8 of the 27 {@code runTaskLater} sites.
 *
 * <p><b>Partial.</b> {@code tryPreMLG} and {@code clutch} land in Task 23.
 */
public final class BlockScan {

    private final AgentState state;
    private final Agent agent;

    public BlockScan(AgentState state, Agent agent) {
        this.state = state;
        this.agent = agent;
    }

    /**
     * Places cobblestone at {@code pos}, shoring up the block below it first when that block is
     * empty too.
     *
     * <p>Ported from {@code placeBlock}. The neighbour tests are upstream's, in upstream's order,
     * and they are asking one question in four increasingly desperate ways: *is there anything
     * solid nearby for this block to look like it is attached to?* A bot towering in mid-air
     * needs a block under the one it stands on, or the placement looks wrong to clients.
     *
     * <p>The delays — 1, 2 and 3 ticks — are upstream's and are what make the two-block
     * placements look like a player doing it rather than a block appearing.
     */
    public void placeBlock(Bot bot, BlockPos pos) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos under = pos.below();

        if (BlockRules.isSpawn(level.getBlockState(under))) {
            placeFinal(bot, under);
            agent.later(2, () -> placeFinal(bot, pos));
        }

        // Any solid neighbour at this level is enough.
        for (BlockPos side : sides(pos)) {
            if (!BlockRules.isSpawn(level.getBlockState(side))) {
                placeFinal(bot, pos);
                return;
            }
        }

        // Failing that, any solid neighbour one level down, plus support beneath.
        boolean edge = false;
        for (BlockPos side : sides(under)) {
            if (!BlockRules.isSpawn(level.getBlockState(side))) {
                edge = true;
            }
        }

        if (edge && BlockRules.isSpawn(level.getBlockState(under))) {
            placeFinal(bot, under);
            agent.later(2, () -> placeFinal(bot, pos));
            return;
        }

        // Failing that, a diagonal, which needs a bridge block placed toward it first.
        boolean bridged = false;

        if (!BlockRules.isSpawn(level.getBlockState(under.offset(1, 0, 1)))
                || !BlockRules.isSpawn(level.getBlockState(under.offset(1, 0, -1)))) {
            BlockPos bridge = under.east();

            if (BlockRules.isSpawn(level.getBlockState(bridge))) {
                placeFinal(bot, bridge);
            }

            bridged = true;
        } else if (!BlockRules.isSpawn(level.getBlockState(under.offset(-1, 0, 1)))
                || !BlockRules.isSpawn(level.getBlockState(under.offset(-1, 0, -1)))) {
            BlockPos bridge = under.west();

            if (BlockRules.isSpawn(level.getBlockState(bridge))) {
                placeFinal(bot, bridge);
            }

            bridged = true;
        }

        if (bridged) {
            agent.later(1, () -> {
                if (BlockRules.isSpawn(level.getBlockState(under))) {
                    placeSound(level, pos);
                    placeFinal(bot, under);
                }
            });

            agent.later(3, () -> {
                placeSound(level, pos);
                placeFinal(bot, pos);
            });
            return;
        }

        placeSound(level, pos);
        placeFinal(bot, pos);
    }

    /**
     * Puts cobblestone at {@code pos}, and turns lava directly below into cobblestone too.
     *
     * <p>Ported from {@code placeFinal}. The lava rule is upstream's: a bot towering out of a
     * lava lake seals the surface under itself as it goes.
     */
    private void placeFinal(Bot bot, BlockPos pos) {
        ServerLevel level = (ServerLevel) bot.level();

        if (level.getBlockState(pos).getBlock() == Blocks.COBBLESTONE) {
            return;
        }

        placeSound(level, pos);
        bot.setItem(new ItemStack(Items.COBBLESTONE));
        level.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());

        BlockPos under = pos.below();

        if (level.getBlockState(under).getBlock() == Blocks.LAVA) {
            level.setBlockAndUpdate(under, Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    private static void placeSound(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1f, 1f);
    }

    /** The four horizontal neighbours, in upstream's order: +X, -X, +Z, -Z. */
    private static List<BlockPos> sides(BlockPos pos) {
        return List.of(pos.east(), pos.west(), pos.south(), pos.north());
    }
}
```

Upstream built those neighbour groups as `HashSet`s and iterated them, so the order was
unspecified; the `List` above fixes an order. That is a real difference wherever two neighbours
would both match, but the loops only ever set a boolean, so the outcome is the same. The first loop
above returns early where upstream's set both `a = true` and kept iterating — same result, one
fewer world read. **Confirm that reading before simplifying**: if the loop body ever gains a side
effect, the early return changes behaviour.

- [ ] **Step 3: Write `checkDown` and `checkUp`**

Append to `Navigation`. Both are long; both are translated statement by statement.

```java
    /**
     * Digs downward when the target is below and out of sight.
     *
     * <p>Ported from {@code checkDown}. Two ways in: the bot is in the same column and more than
     * one block above the target, or it is more than ten blocks above and within ten horizontally.
     * Either way it mines the block it is standing on.
     *
     * @return true when the bot is now mining, meaning {@code tickBot} must stop here
     */
    public boolean checkDown(Bot bot, Vec3 targetPos, boolean sameColumn) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();

        // Either ray reaching the target means there is no need to dig.
        if (LegacyUtils.checkFreeSpace(level, pos, targetPos)
                || LegacyUtils.checkFreeSpace(level, bot.getEyePosition(), targetPos)) {
            return false;
        }

        List<BlockPos> standing = bot.getStandingOn();

        if (sameColumn && BotMath.floorY(pos) > BotMath.floorY(targetPos) + 1) {
            if (standing.isEmpty()) {
                return false;
            }

            mineBelow(bot, standing.get(0));
            return true;
        }

        double horizontal = Math.hypot(targetPos.x - pos.x, targetPos.z - pos.z);

        if (BotMath.floorY(pos) > BotMath.floorY(targetPos) + 10 && horizontal < 10) {
            if (standing.isEmpty()) {
                return false;
            }

            mineBelow(bot, standing.get(0));
            return true;
        }

        return false;
    }

    /**
     * Towers upward when the target is above.
     *
     * <p>Ported from {@code checkUp}, about 150 lines. Three distinct cases, keyed on what is in
     * the three blocks at and above the bot's feet:
     *
     * <ul>
     * <li><b>All three clear.</b> Place cobblestone at the bot's feet and jump — the tower. The
     *     jump impulse differs depending on whether the target is within 16 blocks, and a bot
     *     under {@code noJump} gets a straight upward shove instead.
     * <li><b>Feet and waist clear, head blocked.</b> Mine the block overhead and nudge toward the
     *     block's centre without jumping.
     * <li><b>Stuck in the same column with the waist clear.</b> Mine the block underfoot.
     * </ul>
     *
     * @return true when the bot is now towering or mining
     */
    public boolean checkUp(Bot bot, LivingEntity livingTarget, Vec3 target,
                           boolean withinTargetXZ, boolean sameXZ) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        Vec3 targetPos = livingTarget.position();

        boolean above = LevelRules.aboveGround(level, pos);
        BlockPos ahead = facingBlock(bot);

        // A distant target under open sky is not worth towering toward. The horizontal
        // distance is upstream's: both Y values zeroed before measuring.
        if (ahead == null || BlockRules.isBreak(level.getBlockState(ahead))) {
            if (Math.hypot(targetPos.x - pos.x, targetPos.z - pos.z) >= 16 && above) {
                return false;
            }
        }

        if (BotMath.floorY(pos) >= BotMath.floorY(targetPos) - 1) {
            return false;
        }

        BlockPos feet = BlockPos.containing(pos);
        BlockState m0 = level.getBlockState(feet);
        BlockState m1 = level.getBlockState(feet.above());
        BlockState m2 = level.getBlockState(feet.above(2));

        boolean clear0 = BlockRules.isBreak(m0);
        boolean clear1 = BlockRules.isBreak(m1);
        boolean clear2 = BlockRules.isBreak(m2);

        if (clear0 && clear1 && clear2) {
            return tower(bot, livingTarget, target, feet, m0, withinTargetXZ);
        }

        if (clear0 && clear1) {
            BlockPos overhead = feet.above(2);
            bot.look(Direction.UP);
            mining.preBreak(bot, overhead, ScanOffset.ABOVE);

            if (bot.isBotOnGround()) {
                nudgeToCentre(bot, 0);
            }

            return true;
        }

        if (sameXZ && clear1) {
            List<BlockPos> standing = bot.getStandingOn();

            if (!standing.isEmpty()
                    && standing.get(0).getY() == BotMath.floorY(pos)
                    && !BlockRules.isBreak(level.getBlockState(standing.get(0)))) {
                mineBelow(bot, standing.get(0));
                return true;
            }
        }

        return false;
    }

    /** The tower step: sneak, punch, place at the feet, then jump. */
    private boolean tower(Bot bot, LivingEntity livingTarget, Vec3 target, BlockPos feet,
                          BlockState m0, boolean withinTargetXZ) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();

        bot.setItem(new ItemStack(Items.COBBLESTONE));
        cancelMiningAnim(bot);
        bot.look(Direction.DOWN);

        // Upstream's comment: "maybe put this in lower if statement onGround()". The water
        // guard stops a bot in water sealing itself in.
        if (m0.getBlock() != Blocks.WATER) {
            agent.later(3, () -> {
                bot.sneak();
                bot.setItem(new ItemStack(Items.COBBLESTONE));
                bot.punch();
                bot.look(Direction.DOWN);

                agent.later(1, () -> bot.look(Direction.DOWN));

                blockScan.placeBlock(bot, feet);

                if (!state.towerList.containsKey(bot) && withinTargetXZ) {
                    state.towerList.put(bot, bot.position());
                }
            });
        }

        if (!bot.isBotOnGround()) {
            return false;
        }

        if (livingTarget.position().distanceTo(pos) < 16) {
            if (state.noJump.contains(bot)) {
                // Straight up, next tick. A bot that must not jump still has to rise.
                agent.later(1, () -> bot.setVelocity(new MotionVec(0, 0.5, 0)));
                return false;
            }

            MotionVec vector = MotionVec.of(target.subtract(pos)).normalize();
            bot.stand();

            MotionVec move = bot.getVelocity().add(vector);
            if (move.length() > 1) {
                move.normalize();
            }
            move.multiply(0.1).setY(0.5);

            bot.setVelocity(move);
            return true;
        }

        nudgeToCentre(bot, 0.5);
        return true;
    }

    /** Mines the block underfoot: look down, nudge, and start breaking. */
    private void mineBelow(Bot bot, BlockPos block) {
        bot.look(Direction.DOWN);
        mining.downMine(bot, block);
        mining.preBreak(bot, block, ScanOffset.BELOW);
    }

    /**
     * Pushes the bot toward the centre of its own block, with {@code upward} vertical impulse.
     *
     * <p>Upstream repeated this four times with different Y values. Same arithmetic as
     * {@code Mining.downMine}'s first nudge.
     */
    private void nudgeToCentre(Bot bot, double upward) {
        Vec3 pos = bot.position();
        Vec3 centre = new Vec3(Math.floor(pos.x) + 0.5, pos.y, Math.floor(pos.z) + 0.5);

        MotionVec vector = MotionVec.of(centre.subtract(pos));

        if (vector.length() > 1) {
            vector.normalize();
        }

        vector.multiply(0.1).setY(upward);
        bot.addVelocity(vector);
    }

    /** The block one step in the bot's facing direction, at waist height. */
    private static BlockPos facingBlock(Bot bot) {
        Direction dir = bot.getDirection();

        if (dir.getAxis().isVertical()) {
            return null;
        }

        return BlockPos.containing(bot.position()).above().relative(dir);
    }
```

`Navigation` already holds `Agent`; it now also needs `Mining` and `BlockScan`, so widen its
constructor to `(AgentState, Agent, Mining, BlockScan)` and update `LegacyAgent` to construct them
in dependency order — `Mining` and `BlockScan` first, then `Navigation`, then `BotBehaviors`.
Task 21 adds the fifth parameter.

Two things to check against the original rather than trusting the above. Upstream's `checkUp`
computed its horizontal distance by cloning both locations and calling `setY(0)` on each, then
`a.distance(b)`; `Math.hypot` on the X and Z differences is the same number. And upstream's third
case tested `block.getLocation().getBlockY() == playerNPC.getLocation().getBlockY()` — the *bot's*
Y, not the target's. Keep it that way.

- [ ] **Step 4: Wire checks 13 and 14 into `tickBot`**

After `checkObstacles`, before the `switch`:

```java
            if (navigation.checkDown(bot, livingTarget.position(), bothXZ)) {
                return;
            }

            if ((withinTargetXZ || sameXZ)
                    && navigation.checkUp(bot, livingTarget, target, withinTargetXZ, sameXZ)) {
                return;
            }
```

Note that `checkDown` takes `bothXZ` while `checkUp`'s guard is `withinTargetXZ || sameXZ` — the
same expression, written twice by upstream. Kept, because `bothXZ` is assigned before either and
collapsing them would hide that `checkSide` in Task 21 uses `bothXZ` again for a third purpose.

Also note `checkDown` receives `livingTarget.position()` and **not** the offset `target`. Upstream
passed `livingTarget.getLocation()` there and `target` to `checkUp`. That asymmetry is deliberate:
digging aims at the real target, towering aims at the offset ring.

- [ ] **Step 5: Run the GameTests**

```bash
./gradlew runGameTestServer
```

Expected: `All 84 required tests passed :)`.

`a_bot_towers_toward_a_target_above_it` is the fragile one. If the bot places a block and does not
rise, check that `feet` is captured before the 3-tick delay runs — the bot has moved by then, and
using `BlockPos.containing(bot.position())` inside the lambda instead of the captured `feet` places
the block in the wrong place, which is a bug upstream did not have because it captured `place` the
same way.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java
git commit -m "feat: add vertical navigation and block placement"
```

---
# Phase 7: Surroundings, behaviours, assembly

## Task 20: `SurroundingScan.checkNearby`

280 lines, the largest method in the port, alone in its own file by spec §4.3. It answers one
question — *what should this bot break to get at its target?* — and returns the {@link ScanOffset}
naming it, having already started the break.

Read it in full, twice. It is worth the time:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '486,765p'
```

### The decision this task turns on

The method has two halves. The first scans the bot's own footprint for fences. The second is a
`switch (dir)` with four arms — NORTH, SOUTH, EAST, WEST — and **the four arms are byte-identical
except for the direction constants and the `ScanOffset` names**. Upstream copy-pasted them;
partway through the file its own comment reads "wow this repeated code is so bad lmao".

Porting 4 × 55 identical lines produces a 280-line method nobody will read, in which a single
transposed constant is invisible. So: **extract one arm, parameterised by `Direction`**, and take
on the proof obligation that the extraction is faithful. That obligation is discharged by a test
per direction, which is cheaper than the review of the duplicated version and strictly more
convincing.

This is the one place in the plan that deviates from "extract, do not rewrite". It is a
deduplication, not a redesign: the resulting code has the same control flow, the same world reads
in the same order, and the same return values. If any arm turns out to differ from the others,
**stop and write all four out longhand** — a difference means upstream's copies diverged, and the
divergence is the behaviour.

To find out before writing: diff the arms against each other.

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java \
  | sed -n '556,596p' | sed 's/NORTH/DIR/g; s/-1)/OFF)/g' > /tmp/arm-north.txt
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java \
  | sed -n '597,637p' | sed 's/SOUTH/DIR/g; s/ 1)/OFF)/g' > /tmp/arm-south.txt
diff /tmp/arm-north.txt /tmp/arm-south.txt
```

Adjust the line numbers to the arms as they actually appear. A clean diff, modulo the offset
rewriting, is the evidence that the extraction is safe. Record the result in the commit message.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/agent/legacy/SurroundingScan.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/SurroundingScanTests.java` (new)

- [ ] **Step 1: Confirm the four arms are identical**

Run the diff above. Do not proceed on the extraction until you have looked at the output.

- [ ] **Step 2: Write the failing GameTests**

`src/gametest/java/net/nuggetmc/tplus/gametest/SurroundingScanTests.java`:

```java
package net.nuggetmc.tplus.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.nuggetmc.tplus.agent.legacy.LegacyAgent;
import net.nuggetmc.tplus.agent.legacy.ScanOffset;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;

/**
 * Tests for the surrounding scan.
 *
 * <p>The four directional arms of {@code checkNearby} are identical upstream and are extracted
 * into one parameterised body here. These tests are the proof that the extraction is faithful:
 * one per direction, all four expecting the same answer in a mirrored world.
 */
@ForEachTest(groups = SurroundingScanTests.GROUP)
public final class SurroundingScanTests {

    public static final String GROUP = "bot.scan";

    private SurroundingScanTests() {
    }

    private static Bot spawn(ExtendedGameTestHelper helper, BotRegistry registry,
                             BlockPos relative, float yaw) {
        ServerLevel level = helper.getLevel();
        Bot bot = BotFactory.spawn(registry, level,
                Vec3.atBottomCenterOf(helper.absolutePos(relative)), yaw, 0f,
                BotGameProfiles.create("ScanBot", null), false);
        bot.setGameMode(GameType.SURVIVAL);
        return bot;
    }

    /** Yaw that makes {@code Entity.getDirection()} return {@code dir}. */
    private static float yawFor(Direction dir) {
        return switch (dir) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> throw new IllegalArgumentException("horizontal only: " + dir);
        };
    }

    /** The at-head-height scan finds a solid block one step ahead, in every direction. */
    private static void wallAhead(ExtendedGameTestHelper helper, Direction dir, ScanOffset expected) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        registry.setAgent(agent);

        BlockPos origin = new BlockPos(7, 1, 7);
        Bot bot = spawn(helper, registry, origin, yawFor(dir));

        helper.assertValueEqual(bot.getDirection(), dir, "the bot must be facing " + dir);

        BlockPos wall = origin.above().relative(dir);
        helper.setBlock(wall, Blocks.STONE);

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(Vec3.atCenterOf(origin.relative(dir, 4))), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        ScanOffset result = agent.surroundingScan().checkNearby(bot, target);

        helper.assertValueEqual(result, expected, "scan result facing " + dir);

        registry.reset();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void scan_finds_a_wall_to_the_north(ExtendedGameTestHelper helper) {
        wallAhead(helper, Direction.NORTH, ScanOffset.NORTH);
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void scan_finds_a_wall_to_the_south(ExtendedGameTestHelper helper) {
        wallAhead(helper, Direction.SOUTH, ScanOffset.SOUTH);
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void scan_finds_a_wall_to_the_east(ExtendedGameTestHelper helper) {
        wallAhead(helper, Direction.EAST, ScanOffset.EAST);
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void scan_finds_a_wall_to_the_west(ExtendedGameTestHelper helper) {
        wallAhead(helper, Direction.WEST, ScanOffset.WEST);
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_fence_in_the_bots_own_footprint_wins_over_the_wall_ahead(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        registry.setAgent(agent);

        BlockPos origin = new BlockPos(7, 1, 7);

        // The bot must STRADDLE a block boundary for this path to be reachable at all, and that
        // is not obvious. footprintOffset only fires when the fence is one whole block away on
        // one axis, and the corners it scans come from the bot's own 0.6-wide bounding box — a
        // bot centred in its block has all four corners inside that one block, so no candidate
        // is ever one block away. Spawning on the x boundary makes the box span two columns.
        // An earlier draft of this plan spawned centred and the test could not pass.
        Vec3 straddling = helper.absoluteVec(new Vec3(8.0, 1, 7.5));
        Bot bot = BotFactory.spawn(registry, helper.getLevel(), straddling,
                yawFor(Direction.NORTH), 0f, BotGameProfiles.create("ScanBot", null), false);
        bot.setGameMode(GameType.SURVIVAL);

        // The footprint scan runs BEFORE the directional switch, so a fence beside the bot is
        // found even though there is also a wall in front of it. It only reports a fence when
        // the bot is facing across the fence's axis — facing north finds one east or west.
        helper.setBlock(origin.east(), Blocks.OAK_FENCE);
        helper.setBlock(origin.above().north(), Blocks.STONE);

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(Vec3.atCenterOf(origin.north(4))), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        ScanOffset result = agent.surroundingScan().checkNearby(bot, target);

        helper.assertValueEqual(result, ScanOffset.EAST_D,
                "the footprint fence must win, at foot level");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void an_open_path_scans_to_nothing(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        registry.setAgent(agent);

        BlockPos origin = new BlockPos(7, 1, 7);
        Bot bot = spawn(helper, registry, origin, yawFor(Direction.NORTH));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(Vec3.atCenterOf(origin.north(4))), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        helper.assertTrue(agent.surroundingScan().checkNearby(bot, target) == null,
                "nothing in the way must scan to null");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "15x6x15", floor = true)
    static void a_walkable_step_down_scans_to_nothing(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        LegacyAgent agent = new LegacyAgent(registry);
        registry.setAgent(agent);

        BlockPos origin = new BlockPos(7, 2, 7);
        helper.setBlock(origin.below(), Blocks.STONE);
        Bot bot = spawn(helper, registry, origin, yawFor(Direction.NORTH));

        // A block at knee height with clear air above both it and the bot is a step, not an
        // obstacle — upstream's late "return null" for the _D and _D_2 offsets. Without it a
        // bot mines every stair it could have walked up.
        helper.setBlock(origin.north(), Blocks.STONE);

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(Vec3.atCenterOf(origin.north(4))), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        helper.assertTrue(agent.surroundingScan().checkNearby(bot, target) == null,
                "a walkable step must not be mined");

        registry.reset();
        helper.succeed();
    }
```

`LegacyAgent.surroundingScan()` is a new accessor these tests need; add it alongside
`targeting()`.

- [ ] **Step 3: Write `SurroundingScan`**

```java
package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.agent.Agent;
import net.nuggetmc.tplus.agent.AgentState;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.motion.BotMath;
import net.nuggetmc.tplus.util.BotUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a bot should break to reach its target, and where.
 *
 * <p>Ported from {@code LegacyAgent.checkNearby}, 280 lines, alone in this file by spec §4.3.
 * It has a side effect: whatever it decides on, it has already called {@code preBreak} for by the
 * time it returns.
 *
 * <p>Upstream's four directional arms were byte-identical copies and are one parameterised body
 * here — see the task notes and the per-direction tests for why that is safe.
 */
public final class SurroundingScan {

    private final AgentState state;
    private final Agent agent;
    private final Mining mining;

    public SurroundingScan(AgentState state, Agent agent, Mining mining) {
        this.state = state;
        this.agent = agent;
        this.mining = mining;
    }

    /**
     * Finds the block standing between {@code bot} and {@code target} and starts breaking it.
     *
     * @return the offset naming what is being broken, or null when nothing is in the way
     */
    public @Nullable ScanOffset checkNearby(Bot bot, LivingEntity target) {
        ServerLevel level = (ServerLevel) bot.level();

        bot.faceLocation(target.position());

        Direction dir = bot.getDirection();

        // Phase one: the bot's own footprint. Runs before the directional scan, so a fence
        // beside the bot beats a wall in front of it.
        ScanOffset footprint = scanFootprint(bot, level, dir);
        if (footprint != null) {
            return footprint;
        }

        if (dir.getAxis().isVertical()) {
            return null;
        }

        // Phase two: one step in the facing direction, at three heads of height.
        Scan scan = scanAhead(bot, level, dir);

        if (scan == null) {
            return null;
        }

        // Late rejections: a knee-height block with clear air above is a step, not a wall.
        if (isWalkableStep(bot, level, dir, scan)) {
            return null;
        }

        if (isDuckable(bot, level, dir, scan)) {
            return null;
        }

        if (scan.offset() == ScanOffset.BELOW) {
            state.noJump.add(bot);
            agent.later(15, () -> state.noJump.remove(bot));

            bot.look(Direction.DOWN);
            mining.downMine(bot, scan.pos());
        } else if (scan.offset() == ScanOffset.ABOVE) {
            bot.look(Direction.UP);
        }

        mining.preBreak(bot, scan.pos(), scan.offset());
        return scan.offset();
    }

    /** A chosen block and the offset naming it. */
    private record Scan(BlockPos pos, ScanOffset offset) {
    }

    /**
     * Scans the four corners of the bot's own bounding box for a fence, nearest first.
     *
     * <p>Upstream's first loop. The corners come from the bounding box with {@code maxX - 0.01}
     * and {@code maxZ - 0.01} — the same trick {@code Bot.isFallBlocked} uses, so a box that
     * ends exactly on a boundary does not sample the next block along.
     *
     * <p>A fence only counts when it is one block away on exactly one axis **and** the bot is
     * facing across that axis: a bot facing north reports a fence to its east or west, never one
     * directly ahead. That is upstream's rule and it is why the footprint scan does not simply
     * duplicate the directional one.
     */
    private @Nullable ScanOffset scanFootprint(Bot bot, ServerLevel level, Direction dir) {
        // Reachability note, because it is easy to think this scan covers the bot's neighbours:
        // it does not. The corners come from a 0.6-wide bounding box, so a bot centred in its
        // block yields four candidates all inside that one block, and footprintOffset's
        // one-block displacement test can never match. This path only fires for a bot straddling
        // a block boundary — which, mid-jump between two blocks, is most of the time.
        AABB box = bot.getBotBoundingBox();
        Vec3 pos = bot.position();

        double[] xs = {box.minX, box.maxX - 0.01};
        double[] zs = {box.minZ, box.maxZ - 0.01};

        List<BlockPos> footprint = new ArrayList<>();

        for (double x : xs) {
            for (double z : zs) {
                BlockPos candidate = new BlockPos((int) Math.floor(x), BotMath.floorY(pos),
                        (int) Math.floor(z));

                if (!footprint.contains(candidate)) {
                    footprint.add(candidate);
                }
            }
        }

        footprint.sort((a, b) -> Double.compare(
                BotUtils.getHorizSqDist(a, pos), BotUtils.getHorizSqDist(b, pos)));

        for (BlockPos candidate : footprint) {
            boolean up = false;
            BlockPos found = candidate;

            if (!BlockRules.isFence(level.getBlockState(found))) {
                up = true;
                found = candidate.above();

                if (!BlockRules.isFence(level.getBlockState(found))) {
                    continue;
                }
            }

            ScanOffset offset = footprintOffset(bot, dir, found, up);

            if (offset != null) {
                bot.faceLocation(Vec3.atCenterOf(found));
                mining.preBreak(bot, found, offset);
                return offset;
            }
        }

        return null;
    }

    /**
     * The offset for a footprint fence, or null when the geometry does not qualify.
     *
     * <p>Upstream's four-way if-chain. A fence at +X with the bot facing north or south is EAST
     * (or EAST_D when found at foot level rather than one up), and so on for the other three.
     */
    private static @Nullable ScanOffset footprintOffset(Bot bot, Direction dir,
                                                        BlockPos found, boolean up) {
        BlockPos botPos = BlockPos.containing(bot.position());

        int dx = found.getX() - botPos.getX();
        int dz = found.getZ() - botPos.getZ();

        boolean facingZ = dir == Direction.NORTH || dir == Direction.SOUTH;
        boolean facingX = dir == Direction.EAST || dir == Direction.WEST;

        if (dx == 1 && dz == 0 && facingZ) {
            return up ? ScanOffset.EAST : ScanOffset.EAST_D;
        }
        if (dx == -1 && dz == 0 && facingZ) {
            return up ? ScanOffset.WEST : ScanOffset.WEST_D;
        }
        if (dx == 0 && dz == 1 && facingX) {
            return up ? ScanOffset.SOUTH : ScanOffset.SOUTH_D;
        }
        if (dx == 0 && dz == -1 && facingX) {
            return up ? ScanOffset.NORTH : ScanOffset.NORTH_D;
        }

        return null;
    }

    /**
     * One step in the facing direction, checked at four heights in upstream's order.
     *
     * <p>This is the parameterised form of upstream's four identical switch arms. Reading it
     * against any one of them, {@code sideAt} is {@code NORTH}, {@code sideDown} is
     * {@code NORTH_D}, {@code sideDown2} is {@code NORTH_D_2} and {@code sideUp} is
     * {@code NORTH_U} — all derived from {@link ScanOffset#sideUp()}/{@link ScanOffset#sideDown()}
     * rather than named, which is what makes one body cover four directions.
     */
    private @Nullable Scan scanAhead(Bot bot, ServerLevel level, Direction dir) {
        BlockPos botPos = BlockPos.containing(bot.position());
        BlockPos ahead = botPos.above().relative(dir);

        ScanOffset sideAt = sideAtFor(dir);
        ScanOffset sideDown = sideAt.sideDown();
        ScanOffset sideDown2 = sideDown.sideDown();

        // Head height first.
        if (BlockRules.blocksPath(level.getBlockState(ahead))) {
            return new Scan(ahead, sideAt);
        }

        // Then knee height.
        BlockPos kneeAhead = ahead.below();
        if (BlockRules.blocksPath(level.getBlockState(kneeAhead))) {
            return new Scan(kneeAhead, sideDown);
        }

        // Then a fence two down — a fence is short, so it only obstructs from below.
        BlockPos lowAhead = ahead.below(2);
        if (BlockRules.isFence(level.getBlockState(lowAhead))) {
            return new Scan(lowAhead, sideDown2);
        }

        // Nothing ahead. The bot may still be wedged: check what it is standing on.
        return scanFooting(bot, level, dir, ahead, sideAt);
    }

    /**
     * When nothing ahead is in the way, whether the bot is wedged on its own footing.
     *
     * <p>Upstream's {@code else} branch. Three outcomes, in order: break the block the bot is
     * standing on (BELOW), break the block over its head (ABOVE), or break the block over the
     * space ahead (the {@code _U} offset).
     */
    private @Nullable Scan scanFooting(Bot bot, ServerLevel level, Direction dir,
                                       BlockPos ahead, ScanOffset sideAt) {
        List<BlockPos> standing = bot.getStandingOn();

        if (standing.isEmpty()) {
            return null;
        }

        BlockPos footing = standing.get(0);
        BlockPos botPos = BlockPos.containing(bot.position());
        BlockState footingState = level.getBlockState(footing);

        // "Obstructed" means the footing is level with the bot's own block, or one below it and
        // a fence or gate — a bot standing on a fence post is wedged.
        boolean obstructed = footing.getY() == botPos.getY()
                || (footing.getY() + 1 == botPos.getY()
                        && (BlockRules.isFence(footingState) || BlockRules.isGate(footingState)));

        if (!obstructed) {
            return null;
        }

        BlockState below = level.getBlockState(footing.below());

        if (!BlockRules.isBreak(below) && !BlockRules.isNonSolid(below)) {
            return new Scan(footing, ScanOffset.BELOW);
        }

        BlockPos overhead = botPos.above(2);
        BlockPos overheadAhead = ahead.above();

        if (!BlockRules.isBreak(level.getBlockState(overhead))) {
            return new Scan(overhead, ScanOffset.ABOVE);
        }

        if (!BlockRules.isBreak(level.getBlockState(overheadAhead))) {
            return new Scan(overheadAhead, sideAt.sideUp());
        }

        return null;
    }

    /**
     * Whether a knee- or ankle-height block is a step the bot can simply walk up.
     *
     * <p>Upstream's late rejection for the {@code _D} and {@code _D_2} offsets: with clear air
     * two above both the bot and the block, and the block not a fence or gate, it is a step.
     * Without this a bot mines every staircase it meets.
     */
    private boolean isWalkableStep(Bot bot, ServerLevel level, Direction dir, Scan scan) {
        ScanOffset offset = scan.offset();

        if (!offset.isSideDown() && !offset.isSideDown2()) {
            return false;
        }

        BlockPos botPos = BlockPos.containing(bot.position());
        BlockState blockState = level.getBlockState(scan.pos());

        return BlockRules.isAir(level.getBlockState(botPos.above(2)))
                && BlockRules.isAir(level.getBlockState(scan.pos().above(2)))
                && !BlockRules.isFence(blockState)
                && !BlockRules.isGate(blockState);
    }

    /**
     * Whether an ABOVE or BELOW result can be avoided by walking rather than mining.
     *
     * <p>Upstream's second late rejection: with air over the bot's head and air over the space
     * ahead at the same height, there is a gap to move through.
     */
    private boolean isDuckable(Bot bot, ServerLevel level, Direction dir, Scan scan) {
        if (scan.offset() != ScanOffset.ABOVE && scan.offset() != ScanOffset.BELOW) {
            return false;
        }

        BlockPos botPos = BlockPos.containing(bot.position());
        BlockPos check = botPos.above(2).relative(dir);

        return BlockRules.isAir(level.getBlockState(botPos.above(2)))
                && BlockRules.isAir(level.getBlockState(check));
    }

    /** The at-head-height side offset for a horizontal direction. */
    private static ScanOffset sideAtFor(Direction dir) {
        return switch (dir) {
            case NORTH -> ScanOffset.NORTH;
            case SOUTH -> ScanOffset.SOUTH;
            case EAST -> ScanOffset.EAST;
            case WEST -> ScanOffset.WEST;
            default -> throw new IllegalArgumentException("horizontal only: " + dir);
        };
    }
}
```

Two upstream details worth checking against the original while the file is open, because both are
easy to lose in the extraction. `blocksPath` is the negation of `isBreak`, and upstream's arms
called `checkSideBreak(type)`, which is `!LegacyMats.BREAK.contains(type)` — the same thing, so
confirm the polarity. And the head-height check uses `checkSideBreak` while the fence-two-down
check uses `FENCE` membership directly; they are different predicates and swapping them makes a
bot mine air.

- [ ] **Step 4: Run the GameTests**

```bash
./gradlew runGameTestServer
```

Expected: `All 92 required tests passed :)`.

The four directional tests are the ones that matter. If three pass and one fails, the extraction has
found a real asymmetry — go back to Step 1's diff and write all four arms out longhand.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy/SurroundingScan.java src/gametest/java/net/nuggetmc/tplus/gametest/SurroundingScanTests.java
git commit -m "feat: add the surrounding scan"
```

Include the diff result from Step 1 in the commit message body — it is the evidence for the
deduplication and the next reader will want it.

---

## Task 21: `Navigation.checkSide`

Twenty lines, and the last piece of `tickBot`'s grounded branch. It turns a `SurroundingScan` result
into the three-valued code the `switch` reads.

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '465,486p'
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` — check 15

- [ ] **Step 1: Write `checkSide`**

```java
    /**
     * Decides whether the bot should move, stay put, or move despite being blocked.
     *
     * <p>Ported from {@code checkSide}. The three return values are upstream's and
     * {@code tickBot}'s switch reads them directly:
     *
     * <ul>
     * <li><b>1</b> — nothing in the way. Reset the hand and move.
     * <li><b>0</b> — something to the side, above or below. Stay put; the scan has already
     *     started breaking it.
     * <li><b>2</b> — something in the way that is not one of those. Move anyway.
     * </ul>
     *
     * <p>The early return is the important part: a target within 2.9 blocks with a clear line to
     * the block above it needs no scan at all, so a bot in melee range never starts mining.
     */
    public byte checkSide(Bot bot, LivingEntity target) {
        ServerLevel level = (ServerLevel) bot.level();

        Vec3 a = bot.getEyePosition();
        Vec3 b = target.position().add(0, 1, 0);

        if (bot.position().distanceTo(target.position()) < 2.9
                && LegacyUtils.checkFreeSpace(level, a, b)) {
            behaviors.resetHand(bot, target);
            return 1;
        }

        ScanOffset offset = surroundingScan.checkNearby(bot, target);

        if (offset == null) {
            behaviors.resetHand(bot, target);
            return 1;
        }

        if (offset.isSide() || offset == ScanOffset.BELOW || offset == ScanOffset.ABOVE) {
            return 0;
        }

        return 2;
    }
```

`isSide()` is true for every offset except `ABOVE`, `BELOW`, `AT` and `AT_D` — so the second
condition reduces to "anything except `AT` and `AT_D`". Upstream wrote it the long way and it is
left long, because the two spellings stop meaning the same thing the moment a constant is added to
`ScanOffset`.

`Navigation` now needs `BotBehaviors` and `SurroundingScan`, and `BotBehaviors` already needs
`Navigation` — a cycle. Break it with setter injection in `LegacyAgent`'s constructor rather than
merging the classes:

```java
        this.mining = new Mining(state, this);
        this.blockScan = new BlockScan(state, this);
        this.surroundingScan = new SurroundingScan(state, this, mining);
        this.navigation = new Navigation(state, this, mining, blockScan, surroundingScan);
        this.behaviors = new BotBehaviors(state, navigation, mining);

        navigation.setBehaviors(behaviors);
```

with a one-line setter on `Navigation` and a comment naming the cycle. Upstream had no cycle because
all of this was one class; the cycle is the cost of the split, and a setter is a smaller cost than
re-merging two files spec §4.3 separated on purpose.

- [ ] **Step 2: Wire check 15 into `tickBot`**

```java
            if (bothXZ) {
                sideResult = navigation.checkSide(bot, livingTarget);
            }
```

immediately before the `switch`. `sideResult` is already declared as `byte sideResult = 1`, so a bot
that is not in the same column keeps the default and simply moves — which is the behaviour Task 12
shipped.

- [ ] **Step 3: Build, run everything, commit**

```bash
./gradlew build && ./gradlew runGameTestServer
git add src/main/java/net/nuggetmc/tplus/agent/legacy
git commit -m "feat: add the side check"
```

---

## Task 22: `BotBehaviors`

`miscellaneousChecks` is 146 lines of hazard handling: fire, lava at three heights, magma, an MLG
over water, and the boat trick that carries a bot across a lava lake. Plus `onBoat`, and the
`towerList` reset that belongs in `tickBot`.

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java | sed -n '1215,1395p'
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/BotBehaviors.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` — checks 7 and 9
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java`

- [ ] **Step 1: Write the failing GameTests**

```java
    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x6x9", floor = true)
    static void a_burning_bot_puts_itself_out_with_water(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(4, 1, 4));
        bot.setRemainingFireTicks(100);

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(7.5, 1, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 10);

        // placeWaterDown puts water at the bot's feet and picks it up 5 ticks later, so the
        // assertion is on the fire having gone out, not on the water still being there.
        helper.assertTrue(bot.getRemainingFireTicks() <= 0 || !bot.isOnFire(),
                "a burning bot must extinguish itself");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x6x9", floor = true)
    static void a_bot_standing_in_fire_clears_the_fire(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();

        helper.setBlock(new BlockPos(4, 1, 4), Blocks.FIRE);
        Bot bot = spawn(helper, registry, new BlockPos(4, 1, 4));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(7.5, 1, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 20);

        helper.assertBlockNotPresent(Blocks.FIRE, new BlockPos(4, 1, 4));

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x6x9", floor = true)
    static void a_bot_over_lava_gets_a_boat(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();

        helper.setBlock(new BlockPos(4, 1, 4), Blocks.LAVA);
        Bot bot = spawn(helper, registry, new BlockPos(4, 2, 4));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(7.5, 2, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 10);

        // The boat is the lava-crossing trick: spawn one under the bot, shove it at the target,
        // and remove the boat 20 ticks later. Boats are per-wood entity types in 26.2, so this
        // asserts on the oak one specifically.
        helper.assertEntityPresent(EntityTypes.OAK_BOAT);

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x6x9", floor = true)
    static void the_boat_is_removed_again(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();

        helper.setBlock(new BlockPos(4, 1, 4), Blocks.LAVA);
        Bot bot = spawn(helper, registry, new BlockPos(4, 2, 4));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(7.5, 2, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 60);

        helper.assertEntityNotPresent(EntityTypes.OAK_BOAT);

        registry.reset();
        helper.succeed();
    }
```

Add imports `net.minecraft.world.entity.EntityTypes` and `net.minecraft.world.level.block.Blocks`.

- [ ] **Step 2: Write `miscellaneousChecks` and `onBoat`**

```java
    /**
     * Everything a bot does about its immediate surroundings that is not navigation.
     *
     * <p>Ported from {@code miscellaneousChecks}, 146 lines. The order is upstream's and is
     * roughly head-to-toe: the bot itself, then the block it is in, then its head, then one
     * below, then two below. Several of the branches overlap, and upstream let them.
     */
    public void miscellaneousChecks(Bot bot, LivingEntity target) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();
        BlockPos at = BlockPos.containing(pos);
        boolean nether = bot.isNether();

        if (bot.isBotOnFire() && !nether) {
            mining.placeWaterDown(bot, at);
        }

        Block atBlock = level.getBlockState(at).getBlock();

        if (atBlock == Blocks.FIRE || atBlock == Blocks.SOUL_FIRE) {
            if (!nether) {
                mining.placeWaterDown(bot, at);
                level.playSound(null, at, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f);
            } else {
                // No water in the Nether: punch it out.
                bot.look(Direction.DOWN);
                bot.punch();
                level.playSound(null, at, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f);
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
        }

        if (atBlock == Blocks.LAVA) {
            if (nether) {
                bot.attemptBlockPlace(at, Blocks.COBBLESTONE, false);
            } else {
                mining.placeWaterDown(bot, at);
            }
        }

        BlockPos head = at.above();
        Block headBlock = level.getBlockState(head).getBlock();

        if (headBlock == Blocks.LAVA) {
            if (nether) {
                bot.attemptBlockPlace(head, Blocks.COBBLESTONE, false);
            } else {
                mining.placeWaterDown(bot, head);
            }
        }

        if (headBlock == Blocks.FIRE || headBlock == Blocks.SOUL_FIRE) {
            if (nether) {
                bot.look(Direction.DOWN);
                bot.punch();
                level.playSound(null, head, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f);
                level.setBlockAndUpdate(head, Blocks.AIR.defaultBlockState());
            } else {
                mining.placeWaterDown(bot, head);
            }
        }

        BlockPos under = at.below();
        Block underBlock = level.getBlockState(under).getBlock();

        if (underBlock == Blocks.FIRE || underBlock == Blocks.SOUL_FIRE) {
            bot.look(Direction.DOWN);
            bot.punch();
            level.playSound(null, under, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f);
            level.setBlockAndUpdate(under, Blocks.AIR.defaultBlockState());
        }

        BlockPos under2 = at.below(2);
        Block under2Block = level.getBlockState(under2).getBlock();

        // Upstream wrote `if (under2Type == MAGMA_BLOCK) { if (SPAWN.contains(under2Type)) { … } }`.
        // MAGMA_BLOCK is not in SPAWN, so the inner branch is unreachable and the whole block
        // is dead code. Ported as dead code, deliberately: deleting it would be right, and
        // "fixing" it by removing the inner test would make a bot seal magma it currently
        // ignores. Flagged rather than silently changed.
        if (under2Block == Blocks.MAGMA_BLOCK && BlockRules.isSpawn(level.getBlockState(under2))) {
            bot.attemptBlockPlace(under2, Blocks.COBBLESTONE, true);
        }

        // The water MLG: a bot falling slowly onto water with clear air at eye level puts
        // cobblestone in the water below it.
        if (BotMath.floorY(pos) <= BotMath.floorY(target.position()) + 1
                && !state.miningAnim.containsKey(bot)
                && bot.getVelocity().getY() >= -0.6
                && level.getBlockState(BlockPos.containing(pos.add(0, -0.6, 0))).getBlock() == Blocks.WATER
                && !BlockRules.isNoCrack(level.getBlockState(under2))
                && level.getBlockState(BlockPos.containing(bot.getEyePosition())).isAir()) {

            if (BlockRules.isWater(level.getBlockState(under))) {
                bot.attemptBlockPlace(under, Blocks.COBBLESTONE, true);
            }
        }

        // Lava just under the bot's feet: boat out.
        if (level.getBlockState(BlockPos.containing(pos.add(0, -0.6, 0))).getBlock() == Blocks.LAVA) {
            boatOverLava(bot, target, level, pos);
        }
    }

    /**
     * Spawns a boat under the bot and shoves it toward the target.
     *
     * <p>Ported from the last block of {@code miscellaneousChecks}. A boat floats on lava, so
     * this is how a bot crosses a lava lake. The boat is removed after 20 ticks whether or not it
     * worked, and the bot is put on a 5-tick cooldown so it cannot spam them.
     *
     * <p>26.2 split boats per wood — {@code EntityTypes.OAK_BOAT}, matching upstream's oak boat
     * item — and moved the constants to {@code EntityTypes}. See plan correction 5.
     */
    private void boatOverLava(Bot bot, LivingEntity target, ServerLevel level, Vec3 pos) {
        if (state.boatCooldown.contains(bot)) {
            return;
        }

        state.boatCooldown.add(bot);

        Vec3 place = pos.add(0, -0.1, 0);

        bot.setItem(new ItemStack(Items.OAK_BOAT));
        bot.look(Direction.DOWN);
        bot.punch();

        Boat boat = EntityTypes.OAK_BOAT.create(level, EntitySpawnReason.COMMAND);

        if (boat == null) {
            state.boatCooldown.remove(bot);
            return;
        }

        boat.snapTo(place.x, place.y, place.z, bot.getYRot(), 0f);
        level.addFreshEntity(boat);
        state.boats.add(boat);

        agent.later(20, () -> {
            if (boat.isAlive()) {
                state.boats.remove(boat);
                boat.discard();
            }
        });

        agent.later(1, () -> bot.look(Direction.DOWN));

        bot.stand();

        MotionVec vector = MotionVec.of(target.position().subtract(bot.position())).normalize();
        vector.multiply(0.8);

        MotionVec move = bot.getVelocity().add(vector).setY(0);
        if (move.length() > 1) {
            move.normalize();
        }
        move.multiply(0.5).setY(0.42);
        bot.setVelocity(move);

        agent.later(5, () -> {
            state.boatCooldown.remove(bot);
            if (bot.isBotAlive()) {
                bot.faceLocation(target.position());
            }
        });
    }

    /**
     * Whether the bot is sitting on one of the boats this agent spawned.
     *
     * <p>Ported from {@code onBoat}. {@code tickBot} treats a bot on a boat as grounded, so it
     * can navigate and attack while floating. The dead-boat sweep is upstream's: the set is only
     * pruned when someone asks.
     */
    public boolean onBoat(Bot bot) {
        Set<Boat> dead = new HashSet<>();
        boolean found = false;

        for (Boat boat : state.boats) {
            if (bot.level() != boat.level()) {
                continue;
            }

            if (!boat.isAlive()) {
                dead.add(boat);
                continue;
            }

            if (bot.position().distanceTo(boat.position()) < 1) {
                found = true;
                break;
            }
        }

        state.boats.removeAll(dead);
        return found;
    }
```

Verify the boat spawn before running — `EntityType.create` has changed signature repeatedly and
`EntitySpawnReason` is the 26.2 name for what used to be `MobSpawnType`:

```bash
grep -nE "public @Nullable T create\(" /tmp/mcsrc/net/minecraft/world/entity/EntityType.java
grep -nE "^\s+[A-Z_]+," /tmp/mcsrc/net/minecraft/world/entity/EntitySpawnReason.java | head
```

`BotBehaviors` now needs `Mining` and `Agent`; extend its constructor.

- [ ] **Step 3: Wire checks 7 and 9, and the boat condition**

In `tickBot`, replace the Task 12 comments:

```java
        behaviors.miscellaneousChecks(bot, livingTarget);
```

```java
        if (waterGround || bot.isBotOnGround() || behaviors.onBoat(bot)) {
            byte sideResult = 1;

            if (state.towerList.containsKey(bot)) {
                // A bot that has climbed above its target is done towering.
                if (BotMath.floorY(pos) > BotMath.floorY(livingTarget.position())) {
                    state.towerList.remove(bot);
                    behaviors.resetHand(bot, livingTarget);
                }
            }
```

- [ ] **Step 4: Run the GameTests and commit**

```bash
./gradlew runGameTestServer
git add src/main/java/net/nuggetmc/tplus/agent/legacy src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java
git commit -m "feat: add hazard handling and the lava boat"
```

Expected: `All 96 required tests passed :)`.

---

## Task 23: `BlockScan.tryPreMLG` and `clutch`

The last two behaviours, and the two that make the bots hard to kill. `tryPreMLG` fires while the
bot is still falling and places cobblestone below it speculatively; `clutch` fires when the bot is
standing over a two-block drop and seals it.

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyBlockCheck.java | sed -n '147,287p'
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockScan.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` — checks 3 and 5
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java`

- [ ] **Step 1: Write the failing GameTests**

```java
    @GameTest(timeoutTicks = 600)
    @EmptyTemplate(value = "9x30x9", floor = true)
    static void a_falling_bot_places_a_block_beneath_itself(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(4, 25, 4));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(4.5, 1, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        float before = bot.getHealth();
        run(registry, bot, 150);

        // tryPreMLG only fires below -0.8 vertical velocity with fewer than 8 no-fall ticks
        // left, and only when two or three blocks below are pass-through with something
        // landable under them. Surviving is the assertion; whether it was the pre-MLG or the
        // water clutch that saved it is not something the test can or should distinguish.
        helper.assertTrue(bot.isAlive(), "the bot must survive");
        helper.assertValueEqual(bot.getHealth(), before, "and take no damage");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x10x9", floor = true)
    static void a_bot_over_a_drop_seals_it(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();

        // A bot on a one-block ledge with air below it and below that, and a solid neighbour
        // to give clutch somewhere to aim.
        helper.setBlock(new BlockPos(4, 5, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 4, 4), Blocks.STONE);
        Bot bot = spawn(helper, registry, new BlockPos(4, 6, 4));

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(4.5, 8, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 60);

        // clutch needs both blocks below the bot to be in SPAWN and the target at or above the
        // bot's height, then places cobblestone one below.
        helper.assertTrue(registry.state().slow.isEmpty() || true, "no assertion on slow");
        helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(4, 5, 4));

        registry.reset();
        helper.succeed();
    }
```

The `slow` line in the second test is deliberately a no-op assertion: `slow` and `noFace` are set
for 12 and 15 ticks respectively and asserting on them is timing-dependent. Delete that line —
it is left here only to say why there is no assertion on it.

- [ ] **Step 2: Write `tryPreMLG`**

```java
    /**
     * Places cobblestone under a falling bot, before it needs a water bucket.
     *
     * <p>Ported from {@code tryPreMLG}. Three gates before anything happens: the bot must be
     * airborne, falling faster than -0.8, and have fewer than 8 no-fall ticks left. Then it tries
     * three blocks down, and failing that two.
     *
     * <p>Upstream's return value is ignored by its only caller and is {@code false} on every path
     * — including the one that places a block. Kept as {@code void}; nothing read it.
     */
    public void tryPreMLG(Bot bot, Vec3 pos) {
        if (bot.isBotOnGround() || bot.getVelocity().getY() >= -0.8 || bot.getNoFallTicks() > 7) {
            return;
        }

        if (tryPreMLG(bot, pos, 3)) {
            return;
        }

        tryPreMLG(bot, pos, 2);
    }

    /**
     * Looks {@code blocksBelow} down from each corner of the bot's footprint.
     *
     * <p>The rule, in upstream's words rearranged: every block between the bot and the candidate
     * must be pass-through, and the candidate must be something the bot would land on but
     * **cannot** place water or vines on — because if it could, {@code onFallDamage} will handle
     * it later and a cobblestone block now would be wasted.
     *
     * @return whether a block was placed
     */
    private boolean tryPreMLG(Bot bot, Vec3 pos, int blocksBelow) {
        ServerLevel level = (ServerLevel) bot.level();
        AABB box = bot.getBotBoundingBox();
        boolean nether = bot.isNether();

        double[] xs = {box.minX, box.maxX - 0.01};
        double[] zs = {box.minZ, box.maxZ - 0.01};

        Set<BlockPos> candidates = new LinkedHashSet<>();

        for (double x : xs) {
            for (double z : zs) {
                int baseY = BotMath.floorY(pos);
                boolean blocked = false;

                // Everything on the way down must be pass-through.
                for (int i = 1; i < blocksBelow; i++) {
                    BlockState between = level.getBlockState(
                            new BlockPos((int) Math.floor(x), baseY - i, (int) Math.floor(z)));

                    if (BlockRules.isSolid(between) || BlockRules.canStandOn(between)) {
                        blocked = true;
                        break;
                    }
                }

                if (blocked) {
                    return false;
                }

                candidates.add(new BlockPos((int) Math.floor(x), baseY - blocksBelow,
                        (int) Math.floor(z)));
            }
        }

        // Keep only candidates that are landable AND unplaceable.
        candidates.removeIf(candidate -> {
            boolean placeable = nether
                    ? BlockPlacement.canPlaceTwistingVines(level, candidate)
                    : BlockPlacement.canPlaceWater(level, candidate, OptionalDouble.empty());

            BlockState state = level.getBlockState(candidate);
            return placeable || (!BlockRules.isSolid(state) && !BlockRules.canStandOn(state));
        });

        if (candidates.isEmpty()) {
            return false;
        }

        // Prefer a candidate with clear air above it, then the nearest horizontally.
        List<BlockPos> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            boolean aClear = level.getBlockState(a.above()).isAir();
            boolean bClear = level.getBlockState(b.above()).isAir();

            if (aClear && !bClear) {
                return -1;
            }
            if (bClear && !aClear) {
                return 1;
            }

            return Double.compare(BotUtils.getHorizSqDist(a, pos), BotUtils.getHorizSqDist(b, pos));
        });

        BlockPos faceTarget = sorted.get(0);
        BlockPos place = faceTarget.above();

        bot.faceLocation(Vec3.atCenterOf(faceTarget));
        bot.look(Direction.DOWN);
        agent.later(1, () -> bot.faceLocation(Vec3.atCenterOf(faceTarget)));

        bot.punch();
        placeSound(level, place);
        bot.setItem(new ItemStack(Items.COBBLESTONE));
        level.setBlockAndUpdate(place, Blocks.COBBLESTONE.defaultBlockState());

        return false;
    }
```

Upstream's second comparator arm reads
`if (!bBlock.getType().isAir() && aBlock.getType().isAir()) return 1;` — which is the **same**
condition as the first arm, not its mirror, so it never fires and the comparator is not
antisymmetric. `List.sort` is allowed to throw `IllegalArgumentException` for that
("Comparison method violates its general contract"). The version above writes the mirror the
comment clearly intended. **This is a deviation**: flag it, and if any test depends on the
original ordering, restore the broken arm and accept the risk upstream accepted.

- [ ] **Step 3: Write `clutch`**

```java
    /**
     * Seals a two-block drop under a bot whose target is above it.
     *
     * <p>Ported from {@code clutch}. Both blocks below must be replaceable and at least one
     * horizontal neighbour of the block directly below must be solid — the block is placed
     * against something, not in mid-air.
     *
     * <p>The bot is put in {@code slow} for 12 ticks and {@code noFace} for 15, which is what
     * stops it turning away mid-placement and walking off its own block. Those two windows are
     * the only writers of either set.
     */
    public void clutch(Bot bot, LivingEntity target) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos botPos = BlockPos.containing(bot.position());

        BlockState below = level.getBlockState(botPos.below());
        BlockState below2 = level.getBlockState(botPos.below(2));

        if (!BlockRules.isSpawn(below) || !BlockRules.isSpawn(below2)) {
            return;
        }

        if (BotMath.floorY(target.position()) < botPos.getY()) {
            return;
        }

        BlockPos place = botPos.below();
        BlockPos anchor = null;

        // Upstream kept the LAST matching neighbour, not the first — no break in the loop.
        for (BlockPos side : sides(place)) {
            if (!BlockRules.isSpawn(level.getBlockState(side))) {
                anchor = side;
            }
        }

        if (anchor == null) {
            return;
        }

        state.slow.add(bot);
        state.noFace.add(bot);

        agent.later(12, () -> {
            bot.stand();
            state.slow.remove(bot);
        });

        agent.later(15, () -> state.noFace.remove(bot));

        Vec3 faceTarget = Vec3.atCenterOf(anchor).add(0, -1.5, 0);

        bot.faceLocation(faceTarget);
        bot.look(Direction.DOWN);
        agent.later(1, () -> bot.faceLocation(faceTarget));

        bot.punch();
        bot.sneak();
        placeSound(level, place);
        bot.setItem(new ItemStack(Items.COBBLESTONE));
        level.setBlockAndUpdate(place, Blocks.COBBLESTONE.defaultBlockState());
    }
```

- [ ] **Step 4: Wire checks 3 and 5**

```java
        blockScan.tryPreMLG(bot, pos);

        if (livingTarget == null) {
            mining.stopMining(bot);
            return;
        }

        blockScan.clutch(bot, livingTarget);
```

`tryPreMLG` runs **before** the no-target return, so a falling bot saves itself even with nothing
to chase. That ordering is upstream's and is easy to get wrong.

- [ ] **Step 5: Run and commit**

```bash
./gradlew runGameTestServer
git add src/main/java/net/nuggetmc/tplus/agent/legacy src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java
git commit -m "feat: add the pre-MLG and clutch placements"
```

Expected: `All 98 required tests passed :)`.

---

## Task 24: Assembly

Three loose ends and the end-to-end pass: `stopAllTasks` has to clear the crack overlays, the region
command needs writing, and the whole thing needs running against a real client.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java`
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java`

- [ ] **Step 1: Write the failing GameTest for the overlay cleanup**

```java
    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "9x6x9", floor = true)
    static void stopping_the_agent_clears_every_crack_overlay(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent();
        Bot bot = spawn(helper, registry, new BlockPos(3, 1, 4));

        helper.setBlock(new BlockPos(4, 2, 4), Blocks.OBSIDIAN);

        var target = helper.makeMockServerPlayer(GameType.SURVIVAL);
        target.snapTo(helper.absoluteVec(new Vec3(7.5, 1, 4.5)), 0f, 0f);
        helper.getLevel().addFreshEntity(target);

        run(registry, bot, 40);
        helper.assertFalse(registry.state().crackList.isEmpty(), "the bot should be mining");

        registry.agent().stopAllTasks();

        // Without this, disabling the agent leaves a half-broken block drawn on every client
        // forever — there is no other packet that clears it.
        helper.assertTrue(registry.state().crackList.isEmpty(), "crackList must be emptied");
        helper.assertTrue(registry.state().mining.isEmpty(), "mining must be emptied");

        registry.reset();
        helper.succeed();
    }
```

- [ ] **Step 2: Override `stopAllTasks`**

```java
    /**
     * Ported from {@code LegacyAgent.stopAllTasks}.
     *
     * <p>Cancelling the tasks is not enough: a cancelled progress task never sends the
     * stage -1 packet that clears the overlay, so every half-broken block stays drawn on every
     * client. This sends those packets by hand.
     *
     * <p>The bot that gets used as the packet source does not matter — {@code broadcastCrack}
     * only needs it to find the server — but there has to be one, so a registry with no bots
     * left has nothing to send through and simply clears the maps. In that case every client
     * has already been told the bot is gone, and the overlay goes with it.
     */
    @Override
    public void stopAllTasks() {
        super.stopAllTasks();

        Bot source = registry == null ? null : registry.bots().stream().findFirst().orElse(null);

        for (Map.Entry<AgentState.BlockRef, Short> entry : state.crackList.entrySet()) {
            if (source != null) {
                BotFactory.broadcastCrack(source, entry.getValue(), entry.getKey().pos(), -1);
            }
        }

        state.crackList.clear();
        state.mining.clear();
    }
```

Upstream iterated with an explicit `Iterator` and called `itr.remove()` inside the loop; clearing
afterwards is the same thing and cannot throw `ConcurrentModificationException`.

- [ ] **Step 3: Add `/tplus region`**

Seven arguments, so it takes the coordinates as two block positions plus three weights:

```java
        root.then(Commands.literal("region")
                .then(Commands.literal("clear").executes(ctx -> {
                    if (TerminatorPlus.registry().agent() instanceof LegacyAgent agent) {
                        agent.targeting().setRegion(null, 0, 0, 0);
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("Region cleared"), true);
                    return 1;
                }))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(ctx -> setRegion(ctx, 0, 0, 0))
                                .then(Commands.argument("weightX", DoubleArgumentType.doubleArg(0))
                                        .then(Commands.argument("weightY", DoubleArgumentType.doubleArg(0))
                                                .then(Commands.argument("weightZ", DoubleArgumentType.doubleArg(0))
                                                        .executes(ctx -> setRegion(ctx,
                                                                DoubleArgumentType.getDouble(ctx, "weightX"),
                                                                DoubleArgumentType.getDouble(ctx, "weightY"),
                                                                DoubleArgumentType.getDouble(ctx, "weightZ")))))))));
```

```java
    /**
     * Confines or biases bots to a box.
     *
     * <p>With all three weights at zero the box is a hard boundary — targets outside it are not
     * candidates at all. With non-zero weights it is a bias: a target that far outside is
     * penalised by weight times the squared distance. See {@code Targeting.weightedRegionDist}.
     */
    private static int setRegion(CommandContext<CommandSourceStack> ctx,
                                 double weightX, double weightY, double weightZ)
            throws CommandSyntaxException {
        if (!(TerminatorPlus.registry().agent() instanceof LegacyAgent agent)) {
            ctx.getSource().sendFailure(Component.literal("No legacy agent is installed."));
            return 0;
        }

        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");

        // encapsulatingFullBlocks covers both blocks entirely, which is what an operator
        // selecting two corners means. new AABB(from).minmax(new AABB(to)) would work too but
        // reads worse.
        AABB region = AABB.encapsulatingFullBlocks(from, to);
        agent.targeting().setRegion(region, weightX, weightY, weightZ);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Region set to " + region + (weightX == 0 && weightY == 0 && weightZ == 0
                        ? " (hard boundary)" : " (weighted)")), true);
        return 1;
    }
```

`AABB.encapsulatingFullBlocks(BlockPos, BlockPos)` is verified present in 26.2, as are
`new AABB(BlockPos)` and `AABB.minmax` if you prefer those.

- [ ] **Step 4: Run everything**

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: `BUILD SUCCESSFUL`, all unit tests passing, `All 99 required tests passed :)`, exit 0.

- [ ] **Step 5: Verify the release jar has no test leakage**

The `gametest` source set must not reach the published artifact. Plan A checked this and the check
is worth repeating now that there are four times as many test classes:

```bash
./gradlew jar
unzip -l build/libs/*.jar | grep -iE "gametest|Test" || echo "clean: no test classes in the jar"
```

Expected: `clean: no test classes in the jar`.

- [ ] **Step 6: End-to-end, against a real client**

```bash
./gradlew runServer > run-server.log 2>&1
```

Work through the behaviours this plan added. Each line is one thing to watch:

```
/gamemode survival
/tplus create Hunter 3
```

- Three bots spawn with skins and converge on you in a ring, not a stack.
- Build a wall between you and one: it mines through, with the crack animation visible.
- Stand on a two-block pillar: a bot towers up to you, sneaking as it places.
- Stand in a fenced pen: bots break the fence rather than jumping at it.
- Jump in water: bots swim after you.
- Drop a bot off a cliff (`/tp`): it looks down, holds a water bucket, and MLGs.
- Hit a bot with a shield-equipped bot nearby (`/tplus bot Hunter1 shield true`): shield hits are
  refused with the block sound.
- `/tplus goal nearestbot` — bots fight each other instead of you.
- `/tplus region <pos> <pos>` with no weights — bots outside the box stop being chased.
- `/tplus agent false` — everything stops, and no half-broken block stays drawn.
- `/tplus removeall` — every bot disappears from the world and the tab list.

- [ ] **Step 7: Audit the whole plan against `master`**

The last thing, and the one Plan A found most defects with. For each translated method, diff the
port against the original by eye:

```bash
git show master:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java > /tmp/legacy-agent.java
```

Work through `/tmp/legacy-agent.java` top to bottom and, for each method, find its new home and
check: the same conditions in the same order, the same constants, the same early returns, the same
collection reads and writes. Plan A's equivalent pass found four silent behaviour changes that
every other tier of testing had missed, including a `%` placeholder that was 0-based instead of
1-based and a set membership test with inverted polarity.

Write down what you find, including the deviations this plan already sanctions, and put the list in
the commit message. The sanctioned ones, for reference:

1. `move`'s finite guard runs unconditionally (Task 12).
2. `LegacyUtils.checkFreeSpace` returns true for a zero-length ray instead of relying on NaN
   comparison (Task 11).
3. `SurroundingScan`'s four directional arms are one parameterised body (Task 20).
4. `tryPreMLG`'s comparator has the mirrored second arm upstream's comment intended (Task 23).
5. `AgentState.forget` clears per-bot entries that upstream leaked (Task 7).
6. `onBotKilledByPlayer` runs on the server thread instead of async (Task 7).
7. `BlockRules.INSTANT_BREAK` uses block constants where upstream named items (Task 13).
8. `Mining.downMine`'s nudge — pending the Bukkit `getLocation` check in Task 18 Step 1.
9. `GroundCheck` empty-shape fallback returns a flat box rather than null (Task 13).
10. The `BotLog` line in `BotRegistry.noteTickFailure` (Task 1).

Anything you find that is not on that list is a bug. Fix it, or add it to the list with a reason.

Task 25 adds two more after this audit runs — `/tplus give` putting the new item in hand
immediately, and `settings addplayerlist` becoming a per-invocation argument rather than a sticky
global. Extend the list rather than re-running the audit for them; neither touches the agent.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: complete the LegacyAgent port

Audited every translated method against master. Deviations, all deliberate and
each commented at its call site: <list>"
```

---

## Task 25: The rest of the command surface

Found by the self-review pass against spec §4.4, which maps `BotCommand` (557 lines) to "a Brigadier
tree for create, remove, list, **configure**". Plan A built create/remove/removeall/list and Tasks 5,
12 and 24 built the action subcommands, `goal`, `region`, `agent`, `drops` and `offsets`. Four of
upstream's subcommands are still missing, and two of them leave real state unreachable:

| Upstream | Status before this task |
|---|---|
| `settings mobtarget` | **Unreachable.** Task 8 added `BotRegistry.mobTarget` and the `LivingChangeTargetEvent` listener that reads it, with nothing to toggle it |
| `settings playertarget` | **Unreachable.** Task 4 added `Bot.setTargetPlayer` and Task 10's `PLAYER` goal reads it, with nothing to set it |
| `give <item>` | Per-bot `/tplus bot <name> hold` exists (Task 5); the bulk form does not |
| `armor <tier>` | Missing entirely |
| `info <bot>` | Missing entirely |

`settings addplayerlist` is deliberately **not** ported as a sticky global. Task 9 exposes the same
capability as `/tplus create <name> <count> playerlist`, which is per-invocation. Brigadier makes
that the natural shape, and a hidden global that changes what `create` does is worse for an
operator than an explicit argument. Recorded as a shape change, not an omission.

Read the originals:

```bash
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/command/commands/BotCommand.java | sed -n '129,300p;336,420p'
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java` — `getBotHealth`, `getBotMaxHealth`

- [ ] **Step 1: Add the two accessors Task 4 deferred**

Task 4 left `getBotHealth` and `getBotMaxHealth` out because their only callers were deferred.
`info` is a caller, so they land now:

```java
    public float getBotHealth() {
        return getHealth();
    }

    public float getBotMaxHealth() {
        return getMaxHealth();
    }
```

- [ ] **Step 2: Add the four subcommands**

In `BotCommands.register`:

```java
        root.then(Commands.literal("mobtarget")
                .executes(ctx -> {
                    boolean on = TerminatorPlus.registry().isMobTarget();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Mob targeting is " + (on ? "enabled" : "disabled")), false);
                    return 1;
                })
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean on = BoolArgumentType.getBool(ctx, "enabled");
                            TerminatorPlus.registry().setMobTarget(on);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Mob targeting is now " + (on ? "enabled" : "disabled")), true);
                            return 1;
                        })));

        root.then(Commands.literal("playertarget")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(BotCommands::setPlayerTarget)));

        root.then(Commands.literal("give")
                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                        .executes(BotCommands::give)));

        root.then(Commands.literal("armor")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ARMOR_TIERS.keySet().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(BotCommands::armor)));

        root.then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            for (Bot bot : TerminatorPlus.registry().bots()) {
                                builder.suggest(bot.getBotName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(BotCommands::info)));
```

- [ ] **Step 3: Add the handlers and the armor table**

```java
    /**
     * The seven armor tiers, ported from {@code BotCommand.armorTierSetup}.
     *
     * <p>Ordered boots, leggings, chestplate, helmet — upstream's order, matching Bukkit's
     * {@code setArmorContents}. The {@code none} tier is four empty stacks, which is how armor
     * is removed.
     */
    private static final Map<String, Item[]> ARMOR_TIERS = Map.of(
            "none", new Item[]{null, null, null, null},
            "leather", new Item[]{Items.LEATHER_BOOTS, Items.LEATHER_LEGGINGS,
                    Items.LEATHER_CHESTPLATE, Items.LEATHER_HELMET},
            "chain", new Item[]{Items.CHAINMAIL_BOOTS, Items.CHAINMAIL_LEGGINGS,
                    Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_HELMET},
            "gold", new Item[]{Items.GOLDEN_BOOTS, Items.GOLDEN_LEGGINGS,
                    Items.GOLDEN_CHESTPLATE, Items.GOLDEN_HELMET},
            "iron", new Item[]{Items.IRON_BOOTS, Items.IRON_LEGGINGS,
                    Items.IRON_CHESTPLATE, Items.IRON_HELMET},
            "diamond", new Item[]{Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS,
                    Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET},
            "netherite", new Item[]{Items.NETHERITE_BOOTS, Items.NETHERITE_LEGGINGS,
                    Items.NETHERITE_CHESTPLATE, Items.NETHERITE_HELMET});

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

    /**
     * Points every live bot at one player.
     *
     * <p>Ported from {@code settings playertarget}. Upstream's message said it plainly and it is
     * worth repeating to the operator: the {@code PLAYER} goal has to be selected separately, or
     * this changes nothing.
     */
    private static int setPlayerTarget(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");

        for (Bot bot : TerminatorPlus.registry().bots()) {
            bot.setTargetPlayer(player.getUUID());
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "All bots now target " + player.getGameProfile().name()
                        + ". Set the goal to 'player' for this to take effect."), true);
        return 1;
    }

    /**
     * Sets every bot's default item.
     *
     * <p>Ported from {@code give}. The default item is what {@code setItem(null)} restores and
     * what {@code ItemUtils} scores for damage, so this is how a bot is armed.
     */
    private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ItemStack stack = ItemArgument.getItem(ctx, "item").createItemStack(1);
        Collection<Bot> bots = TerminatorPlus.registry().bots();

        for (Bot bot : bots) {
            bot.setDefaultItem(stack.copy());
            bot.setItem(null);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set the default item to " + stack.getHoverName().getString()
                        + " for " + bots.size() + " bot(s)"), true);
        return 1;
    }
```

`bot.setItem(null)` after `setDefaultItem` is not upstream's — upstream only set the field, so a bot
already holding something kept it until the next `resetHand`. Putting the new item in hand
immediately is what an operator typing the command expects, and `resetHand` would do it within a few
ticks anyway. **Flagged as a deviation**; drop the line if the delay turns out to matter.

```java
    /**
     * Equips every bot with an armor tier.
     *
     * <p>Ported from {@code armor}. Upstream wrote the Bukkit inventory *and* sent the equipment
     * packets, with the comment "packet sending to ensure"; {@code Bot.setItem(stack, slot)}
     * already does both, so one call per slot is enough.
     */
    private static int armor(CommandContext<CommandSourceStack> ctx) {
        String tier = StringArgumentType.getString(ctx, "tier").toLowerCase();
        Item[] pieces = ARMOR_TIERS.get(tier);

        if (pieces == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + tier + "' is not a valid tier. Available: "
                            + String.join(", ", ARMOR_TIERS.keySet())));
            return 0;
        }

        Collection<Bot> bots = TerminatorPlus.registry().bots();

        for (Bot bot : bots) {
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                ItemStack stack = pieces[i] == null ? ItemStack.EMPTY : new ItemStack(pieces[i]);
                bot.setItem(stack, ARMOR_SLOTS[i]);
            }
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set armor tier '" + tier + "' for " + bots.size() + " bot(s)"), true);
        return 1;
    }

    /**
     * Reports one bot's state.
     *
     * <p>Ported from {@code info}. Upstream ran this asynchronously and wrapped it in a
     * catch-all, because it also did a name lookup that could block; ours reads live entity
     * state and must therefore run on the server thread. Upstream's own comment lists fields it
     * never implemented — creation time, inventory, current target, skin — and those stay
     * unimplemented here.
     */
    private static int info(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Bot bot = TerminatorPlus.registry().byName(name);

        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("No bot named '" + name + "'"));
            return 0;
        }

        Vec3 pos = bot.position();
        MotionVec vel = bot.getVelocity();

        ctx.getSource().sendSuccess(() -> Component.literal(bot.getBotName())
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(
                        "\n  Level: " + bot.level().dimension().location()
                        + "\n  Position: " + BotMath.round2Dec(pos.x) + ", "
                                + BotMath.round2Dec(pos.y) + ", " + BotMath.round2Dec(pos.z)
                        + "\n  Velocity: " + BotMath.round2Dec(vel.getX()) + ", "
                                + BotMath.round2Dec(vel.getY()) + ", " + BotMath.round2Dec(vel.getZ())
                        + "\n  Health: " + BotMath.round1Dec(bot.getBotHealth())
                                + " / " + BotMath.round1Dec(bot.getBotMaxHealth())
                        + "\n  Alive ticks: " + bot.getAliveTicks()
                        + "\n  Kills: " + bot.getKills()
                        + "\n  In player list: " + bot.isInPlayerList())
                        .withStyle(ChatFormatting.RESET)), false);
        return 1;
    }
```

`info` is what finally uses `BotMath.round1Dec` and `round2Dec`, two of the five methods Plan A's
self-review listed as unused-but-kept. `getAliveTicks` is a third. Check the remaining two —
`addVelocity` and `getRandomSetElement` — while you are here: `addVelocity` is used by
`Navigation.swim` and `nudgeToCentre`, and `getRandomSetElement` has no v1 caller now that
`PlayerUtils.randomName` is not ported (correction 6). Delete it, or record it as the last unused
method with its reason.

Add imports: `com.mojang.brigadier.arguments.BoolArgumentType`,
`com.mojang.brigadier.exceptions.CommandSyntaxException`, `net.minecraft.ChatFormatting`,
`net.minecraft.commands.arguments.EntityArgument`,
`net.minecraft.commands.arguments.item.ItemArgument`, `net.minecraft.world.entity.EquipmentSlot`,
`net.minecraft.world.item.Item`, `net.minecraft.world.item.ItemStack`,
`net.minecraft.world.item.Items`, `net.nuggetmc.tplus.motion.BotMath`,
`net.nuggetmc.tplus.motion.MotionVec`, `java.util.Collection`, `java.util.Map`.

`Map.of` takes at most ten key-value pairs, and there are seven tiers — fine, but if a tier is added
it has to become `Map.ofEntries`.

- [ ] **Step 4: Verify by hand**

There is nothing worth a GameTest here: every handler is a registry loop plus a message, and the
state each one writes is already covered — `mobTarget` by Task 8's listener, `targetPlayer` by Task
10's `PLAYER` goal, `defaultItem` by Task 4's attack tests. What needs checking is that the tree
parses and the arguments resolve, which only a real server shows.

```bash
./gradlew runServer > run-server.log 2>&1
```

```
/tplus create Squad 3
/tplus give minecraft:diamond_sword
/tplus armor iron
/tplus info Squad1
/tplus mobtarget
/tplus mobtarget true
/tplus playertarget @s
/tplus goal player
```

Each line is one thing to confirm: the sword appears in every bot's hand and they hit noticeably
harder; the armor renders; `info` prints a position that changes as the bot moves; `mobtarget` with
no argument reports the current value and with `true` lets a zombie chase a bot; and after
`playertarget` plus `goal player` the bots chase you specifically rather than the nearest target.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus
git commit -m "feat: complete the command surface (mobtarget, playertarget, give, armor, info)"
```

---
## Definition of done

- [ ] Every task's commit compiles. No task in this plan ends on a red build: each one's only
      expected failure is the TDD red step, which is always a missing symbol the same task then
      adds.
- [ ] `./gradlew build` succeeds with no warnings introduced by this plan.
- [ ] Every unit test passes.
- [ ] `./gradlew runGameTestServer` reports all required tests passed and exits 0.
- [ ] `./gradlew jar` produces an artifact containing no `gametest` or test classes.
- [ ] A vanilla client connects to `runServer` and needs no mod installed.
- [ ] Every behaviour in Task 24 Step 6 has been seen working by hand.
- [ ] Every command in Task 25 Step 4 has been run against a live server.
- [ ] No state in the mod is unreachable from a command: in particular `mobTarget`,
      `targetPlayer`, `defaultItem`, `offsets`, `drops` and the region weights.
- [ ] Every file in spec §4.4's agent and utility tables has a destination: ported, folded into
      another file, or listed in a correction as deliberately not ported.
- [ ] The audit in Task 24 Step 7 is done and its findings are recorded in the commit message.
- [ ] Spec §4.3's split is real: no file in `agent/legacy` exceeds ~350 lines, and `LegacyAgent`
      holds no mutable state of its own beyond `offsets`.
- [ ] Every deviation from upstream behaviour is commented at the code that deviates, not only in
      this plan.
- [ ] Of the five methods Plan A kept unused, four now have callers (`getAliveTicks`,
      `addVelocity`, `round1Dec`, `round2Dec`). `getRandomSetElement` is deleted or its reason is
      recorded — see Task 25 Step 3.

### What is still not done after this plan

Stated so nobody has to rediscover it:

- **The neural-network AI.** `IntelligenceAgent`, `NeuralNetwork`, `BotData`, `BotNode`,
  `NodeConnections`, `BotDataType`, `ActivationType`, `BotAgent`, `BotSituation`,
  `VerticalDisplacement` — plus the `move` and `tickBot` branches that read them (correction 4) and
  the `/tplus ai` command tree.
- **`Debugger`** (497 lines) and **`BotEnvironmentCommand`** (323), which is the only writer of
  `CUSTOM_MOB_LIST` — so the `CUSTOM_LIST` goal and the custom branches of three other goals are
  reachable but never configured.
- **The public API module.** `Terminator`, `BotManager`, `TerminatorPlusAPI`, `InternalBridge`,
  `AIManager`. The agent works against the concrete `Bot` by spec §4.4's decision to let real
  internal usage drive the interface's shape rather than guessing it up front — and after this plan,
  that usage exists.
- **Narrowing `AgentState`.** Twelve collections in one injected object was the deliberately
  mechanical choice (spec §4.3). Distributing them to owners is now possible, because the
  collaborators exist and their tests pin the behaviour.
