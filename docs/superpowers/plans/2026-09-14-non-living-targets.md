# Targeting non-living entities — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let bots target anything a player could hit — end crystals, boats, minecarts, item
frames, paintings — instead of only `LivingEntity`, then cut a 5.1.0-ALPHA release.

**Architecture:** The targeting pipeline's type widens from `LivingEntity` to `Entity`, which is
almost a pure signature change because every member the agent reads on its target is declared on
`Entity`. Only the `ENTITY` goal's scan widens with it, gated on vanilla's own
`isAttackable() && isPickable()`; the nine living goals are untouched. A separate two-line redirect
sends Ender Dragon hits to the head so they stop being quartered.

**Tech Stack:** Java 25, NeoForge 26.2.0.87, Minecraft 26.2, Gradle 9.2.1, JUnit 5 for unit tests,
NeoForge's GameTest framework for in-world tests, GitHub Actions for CI.

**Spec:** `docs/superpowers/specs/2026-09-14-targeting-non-living-entities-design.md`

## Global Constraints

- **Verify every vanilla signature against
  `build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar`, never a Paper jar.** Paper
  patches vanilla classes and gives wrong answers.
- **`mod_id` stays `tplus`**, the package stays `net.nuggetmc.tplus`, the command stays `/tplus`,
  the jar stays `tplus-<version>.jar`. Only `mod_version` changes in this plan.
- **Fidelity is the point.** Method bodies are translated, not redesigned. Every deliberate
  divergence gets an entry in Plan B's deviation register — entries 36 and 37 here. Do not start a
  new list.
- **Comments explain *why*,** especially where the code looks wrong.
- **`@TestHolder` is required on every GameTest.** Without it the test is silently unregistered and
  the suite still reports success.
- **GameTests share a level, not just a JVM.** The `ENTITY` scan has no range limit. Assert the
  rule — "not the item", "not the spectator" — never that the level is empty.
- **Never construct an `ItemStack` in a static initialiser** ("Components not bound yet"). Inside a
  test method at runtime is fine.
- Commit messages end with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## Commands

```bash
./gradlew build              # compile + 109 unit tests
./gradlew runGameTestServer  # GameTests, headless, ~10s
```

---

## File Structure

| File | Change | Responsibility after the change |
|---|---|---|
| `src/main/java/.../event/TerminatorLocateTargetEvent.java` | Modify | Carries an `Entity` target instead of a `LivingEntity` one |
| `src/main/java/.../agent/legacy/Targeting.java` | Modify | Returns `Entity`; owns `allEntities` and `isTargetable`, the one home for "can a bot hit this" |
| `src/main/java/.../agent/legacy/LegacyAgent.java` | Modify | `Entity` target throughout; owns the dragon-head redirect |
| `src/main/java/.../agent/legacy/BlockScan.java` | Modify | Parameter retype only |
| `src/main/java/.../agent/legacy/BotBehaviors.java` | Modify | Parameter retype only |
| `src/main/java/.../agent/legacy/Navigation.java` | Modify | Parameter retype only |
| `src/main/java/.../agent/legacy/SurroundingScan.java` | Modify | Parameter retype only |
| `src/main/java/.../bot/Bot.java` | Modify | `attack(Entity)` |
| `src/main/java/.../command/BotCommands.java` | Modify | Calls `Targeting.isTargetable` rather than spelling the rule a second way |
| `src/gametest/java/.../EnemyTargetTests.java` | Modify | Gains four tests for the widened scan and the gate |
| `README.md` | Modify | What can be targeted, and the two ways it bites |
| `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` | Modify | Deviation register entries 36 and 37 |
| `docs/backlog.md` | Modify | The `generic <type>` validation gap |
| `gradle.properties` | Modify | `mod_version=5.1.0-ALPHA` |
| `CHANGELOG.md` | Create | Hand-written release notes the workflow reads |
| `.github/workflows/release.yml` | Create | Builds, tests and publishes on a `v*` tag push |

**Unchanged, and deliberately so:** `EnemyTarget.java` — `matches(EntityType<?>, UUID)` already
takes the two fields rather than an entity, and both are on `Entity`. `TargetGoal.java`. Every
unit test. The nine non-`ENTITY` branches of `locateTarget`.

---

## Task 1: Widen the targeting pipeline to `Entity`

A pure type change with **no behaviour change at all** — the `ENTITY` branch still scans
`livingEntities` when this task ends. That is the point: the entire existing suite is the test, and
it must pass untouched. Task 2 is where behaviour moves.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/event/TerminatorLocateTargetEvent.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Targeting.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockScan.java:298`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/BotBehaviors.java:53,79,175,204`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java:82,160,294,361,467`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/SurroundingScan.java:48`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java:482`
- Modify: `src/gametest/java/net/nuggetmc/tplus/gametest/EnemyTargetTests.java:5,70,104,127,175`

**Interfaces:**
- Consumes: nothing.
- Produces: `Targeting.locateTarget(Bot, Vec3)` and `locateTarget(Bot, Vec3, TargetGoal)` both
  return `@Nullable Entity`. `Bot.attack(Entity)`. `TerminatorLocateTargetEvent.getTarget()` returns
  `@Nullable Entity` and `setTarget(@Nullable Entity)`. Every collaborator listed above takes
  `Entity` where it took `LivingEntity`.

- [ ] **Step 1: Confirm the suite is green before touching anything**

A pure refactor is only safe if you know what "unchanged" looked like.

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: BUILD SUCCESSFUL twice. Write down the GameTest count the second run reports — it must be
identical at the end of this task.

- [ ] **Step 2: Widen the event**

In `TerminatorLocateTargetEvent.java`, change the import and the three members. Replace:

```java
import net.minecraft.world.entity.LivingEntity;
```

with:

```java
import net.minecraft.world.entity.Entity;
```

and replace the field, constructor parameter and both accessors:

```java
    private final Bot bot;
    private @Nullable Entity target;

    public TerminatorLocateTargetEvent(Bot bot, @Nullable Entity target) {
        this.bot = bot;
        this.target = target;
    }

    public Bot getBot() {
        return bot;
    }

    public @Nullable Entity getTarget() {
        return target;
    }

    public void setTarget(@Nullable Entity target) {
        this.target = target;
    }
```

Add a paragraph to the class javadoc, immediately before the `ICancellableEvent` paragraph, so the
type change is explained where a reader meets it:

```java
 * <p>The target is an {@code Entity}, not the {@code LivingEntity} upstream's Bukkit event
 * carried. Bots can target end crystals, boats and item frames, none of which are living — see
 * deviation 36. A handler that only ever calls {@code setTarget} is unaffected, because a
 * {@code LivingEntity} still satisfies an {@code Entity} parameter; one that assigns
 * {@code getTarget()} to a {@code LivingEntity} is the case that has to change.
```

- [ ] **Step 3: Widen `Targeting`**

In `Targeting.java`, add the `Entity` import beside the existing entity imports:

```java
import net.minecraft.world.entity.Entity;
```

`LivingEntity` stays imported — `livingEntities` still uses it and nine branches still declare it.

Change both `locateTarget` overloads and `validateCloserEntity` to `Entity`:

```java
    public @Nullable Entity locateTarget(Bot bot, Vec3 pos) {
        return locateTarget(bot, pos, goal);
    }
```

```java
    public @Nullable Entity locateTarget(Bot bot, Vec3 pos, TargetGoal g) {
        ServerLevel level = (ServerLevel) bot.level();
        MinecraftServer server = level.getServer();
        Entity result = null;
```

```java
    private boolean validateCloserEntity(Bot bot, Entity entity, Vec3 pos,
                                         @Nullable Entity incumbent) {
```

**Leave every `case` body alone.** The nine living branches keep `for (LivingEntity entity : ...)`
and assign a `LivingEntity` into an `Entity` local, which is legal and is exactly the "no behaviour
change" property this task is for. The `ENTITY` branch is Task 2's.

- [ ] **Step 4: Widen `Bot.attack`**

In `Bot.java`, change the signature at line 482 and add the `Entity` import:

```java
    public void attack(Entity target) {
        faceLocation(target.position());
        punch();

        double damage = ItemUtils.getLegacyAttackDamage(defaultItem);

        target.hurtServer((ServerLevel) level(), damageSources().playerAttack(this), (float) damage);
    }
```

The body is unchanged: `position()` and `hurtServer` are both declared on `Entity` —
`hurtServer` at `Entity.java:1936`, abstract.

Keep the `LivingEntity` import only if something else in `Bot.java` still uses it; check with
`grep -n LivingEntity src/main/java/net/nuggetmc/tplus/bot/Bot.java` and remove it if the only hits
are comments.

- [ ] **Step 5: Widen the five collaborators**

Change `LivingEntity` to `Entity` in these signatures and add the `Entity` import to each file,
removing the `LivingEntity` import where it becomes unused. **No method body changes anywhere.**

`BlockScan.java:298` → `public void clutch(Bot bot, Entity target)`

`BotBehaviors.java`:
```java
    public void resetHand(Bot bot, Entity target) {
    public void miscellaneousChecks(Bot bot, Entity target) {
    private void waterMlg(Bot bot, Entity target, ServerLevel level, Vec3 pos,
    private void boatOverLava(Bot bot, Entity target, ServerLevel level, Vec3 pos) {
```

`Navigation.java`:
```java
    public void move(Bot bot, Entity livingTarget, Vec3 pos, Vec3 target) {
    public void swim(Bot bot, Vec3 target, Entity livingTarget, boolean anim) {
    public boolean checkUp(Bot bot, Entity livingTarget, Vec3 target,
    private boolean tower(Bot bot, Entity livingTarget, Vec3 target, BlockPos feet,
    public byte checkSide(Bot bot, Entity target) {
```

Keep the parameter name `livingTarget` in `Navigation`. It is now slightly wrong, but it is
upstream's name, it appears in this port's comments, and renaming it would bury the one-line type
change in a hundred-line diff. Note that in the commit message, not with a rename.

`SurroundingScan.java:48` → `public @Nullable ScanOffset checkNearby(Bot bot, Entity target)`

- [ ] **Step 6: Widen `LegacyAgent`**

In `LegacyAgent.java`, add the `Entity` import, change the `tickBot` local at line 131 and the
`attack` signature at line 302:

```java
        Entity livingTarget = targeting.locateTarget(bot, pos);
```

```java
    private void attack(Bot bot, Entity target, Vec3 pos) {
```

`attack`'s body is unchanged in this task. `target.invulnerableTime` is a public field on `Entity`
(`Entity.java:257`), and `target instanceof ServerPlayer player` still compiles.

- [ ] **Step 7: Widen the GameTest helper**

In `EnemyTargetTests.java`, replace the `LivingEntity` import with `Entity`:

```java
import net.minecraft.world.entity.Entity;
```

and change the helper and its three call sites:

```java
    private static Entity locate(BotRegistry registry, Bot bot) {
        return ((LegacyAgent) registry.agent()).targeting().locateTarget(bot, bot.position());
    }
```

Lines 104, 127 and 175 each read `LivingEntity found = locate(...)`; change all three to
`Entity found = locate(...)`.

- [ ] **Step 8: Compile and run the whole suite**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, 109 unit tests pass.

```bash
./gradlew runGameTestServer
```

Expected: BUILD SUCCESSFUL with **exactly the same test count as Step 1**. A different count means
a test was silently unregistered — check `@TestHolder` survived every edit.

If anything failed to compile, the likely cause is a file where `LivingEntity` is now unused and
the import was left, or one where `Entity` was used and the import was not added. Neither is a
design problem; fix and re-run.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: type the targeting pipeline on Entity, not LivingEntity

Pure signature change, no behaviour change: the ENTITY branch still scans
livingEntities, so every goal finds exactly what it found before and the
whole suite passes untouched. Widening the scan is the next commit.

It is close to free because the agent barely uses its target. Every
member read on it anywhere -- position, getEyePosition, isAlive, level,
hurtServer and invulnerableTime -- is declared on Entity, including the
last two, which look like LivingEntity members and are not. So eleven
signatures move and not one method body does.

Navigation keeps its `livingTarget` parameter name. It is upstream's, it
is quoted in this port's comments, and renaming it would bury a one-line
type change inside a hundred-line diff.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Widen the `ENTITY` scan and gate it

Where behaviour changes. End crystals, boats, minecarts, item frames and paintings become
targetable; dropped items, experience orbs and spectators become correctly untargetable.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Targeting.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/EnemyTargetTests.java`

**Interfaces:**
- Consumes: `Targeting.locateTarget` returning `Entity`, from Task 1.
- Produces: `public static boolean Targeting.isTargetable(Entity entity)` — the single home for
  "can a bot hit this". Task 3 calls it from the command layer.

- [ ] **Step 1: Write the four failing tests**

Append these to `EnemyTargetTests.java`, before the closing brace. Add the imports they need at the
top of the file:

```java
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
```

```java
    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_generic_target_finds_an_end_crystal")
    static void a_generic_target_finds_an_end_crystal(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        EndCrystal crystal = helper.spawn(EntityTypes.END_CRYSTAL, new BlockPos(8, 1, 5));

        bot.setEnemyTarget(EnemyTarget.ofTypes(
                Set.of(EntityTypes.END_CRYSTAL), "minecraft:end_crystal"));

        // locateTarget directly, and never a ticked registry: a crystal that actually dies
        // explodes at 6.0F with ExplosionInteraction.BLOCK, and these tests share a level. The
        // blast would break blocks belonging to tests running at other structure positions.
        helper.assertTrue(locate(registry, bot) == crystal,
                "an end crystal is not a LivingEntity and must still be found");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_generic_target_finds_a_boat")
    static void a_generic_target_finds_a_boat(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        Boat boat = helper.spawn(EntityTypes.OAK_BOAT, new BlockPos(8, 1, 5));

        bot.setEnemyTarget(EnemyTarget.ofTypes(
                Set.of(EntityTypes.OAK_BOAT), "minecraft:oak_boat"));

        // The crystal alone would not prove much -- it could have been special-cased. A boat
        // reaches Entity through VehicleEntity rather than directly, so the pair shows the
        // widening is general rather than crystal-shaped.
        helper.assertTrue(locate(registry, bot) == boat,
                "a boat is not a LivingEntity and must still be found");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_dropped_item_is_never_targetable")
    static void a_dropped_item_is_never_targetable(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");

        ItemEntity dropped = helper.spawn(EntityTypes.ITEM, new BlockPos(3, 1, 5));
        dropped.setItem(new ItemStack(Items.DIRT));

        bot.setEnemyTarget(EnemyTarget.ofTypes(Set.of(EntityTypes.ITEM), "minecraft:item"));

        // ItemEntity.isAttackable() is false, so the gate refuses it even though the target
        // names its exact type and it is the nearest thing in the structure. Asserting the rule
        // and not the level: other tests' entities share this level, so "found nothing" would be
        // the wrong assertion -- "did not find the item" is the right one.
        helper.assertFalse(locate(registry, bot) == dropped,
                "a dropped item cannot be hit, so it must never be chosen");

        registry.reset();
        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "11x5x11", floor = true)
    @TestHolder("a_spectator_is_never_targetable")
    static void a_spectator_is_never_targetable(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithEntityGoal();
        Bot hunter = spawn(helper, registry, new BlockPos(1, 1, 5), "Hunter");
        Bot ghost = spawn(helper, registry, new BlockPos(8, 1, 5), "Ghost");

        ghost.setGameMode(GameType.SPECTATOR);

        // Read against another_bot_is_a_valid_target, which is this test with one line removed.
        // Player.isPickable() is `!isSpectator() && super.isPickable()`, so the gate refuses what
        // the old `instanceof LivingEntity` test admitted. A narrowing, and a fix: a spectator
        // cannot be hit by anything, so chasing one was a command that succeeded and did nothing.
        hunter.setEnemyTarget(EnemyTarget.ofEntities(
                Set.of(ghost.getUUID()), "1 entities (player)"));

        helper.assertFalse(locate(registry, hunter) == ghost,
                "a spectator cannot be hit, so it must never be chosen");

        registry.reset();
        helper.succeed();
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./gradlew runGameTestServer
```

Expected: **three of the four fail.** Specifically:

| Test | Now | Why |
|---|---|---|
| `a_generic_target_finds_an_end_crystal` | **FAIL** | `EndCrystal` is not in the `livingEntities` scan |
| `a_generic_target_finds_a_boat` | **FAIL** | nor is `Boat` |
| `a_spectator_is_never_targetable` | **FAIL** | a spectator *is* a `LivingEntity`, so the old scan returns it |
| `a_dropped_item_is_never_targetable` | **passes** | `ItemEntity` is not in the `livingEntities` scan either |

The item test passing now is the point of writing it now: it is a guard against Task 2's own
change, and it only has value if it exists before the scan widens. The other three are the new
capability and the narrowing.

If fewer than three fail, something is wrong — check `@TestHolder` is present on each and that the
suite's total test count went up by exactly four. A test without `@TestHolder` is silently
unregistered and the suite still reports success.

- [ ] **Step 3: Add `allEntities` and `isTargetable` to `Targeting`**

Insert both immediately after the existing `livingEntities` method, so the two scans sit together:

```java
    /**
     * Every entity in the level, living or not.
     *
     * <p>Only the {@code ENTITY} goal uses this. The nine goals above it look for
     * {@code Monster}, {@code Mob}, {@code Raider}, {@code ServerPlayer} or {@code Bot} — all of
     * them {@code LivingEntity} — so widening their scan would cost more and find nothing new.
     *
     * <p>Same shape and the same cost as {@link #livingEntities}: {@code getEntities} walks every
     * entity in the level and type-tests each one, so the living scan was never cheaper in kind.
     */
    private static List<? extends Entity> allEntities(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(Entity.class), e -> true);
    }

    /**
     * Whether a bot could actually land a hit on {@code entity}.
     *
     * <p>Vanilla's own pair and deliberately nothing else. {@code isAttackable} and
     * {@code isPickable} are what decide whether a player's cursor can land on a thing, so a bot
     * that respects them targets exactly what a player could: end crystals, boats, minecarts,
     * item frames, paintings, lead knots and shulker bullets in; dropped items, experience orbs,
     * falling blocks, area-effect clouds, displays, markers and armour-stand markers out.
     *
     * <p>Re-evaluated every tick rather than cached, because several of these are dynamic —
     * {@code AbstractBoat.isPickable()} is {@code !isRemoved()} and
     * {@code AbstractArrow.isPickable()} is {@code super.isPickable() && !isInGround()}.
     *
     * <p><b>Two vanilla entities pass this and can never be damaged, and that is known rather
     * than missed.</b> {@code PrimedTnt} and {@code Interaction} are both pickable and inherit
     * {@code isAttackable() == true}, but each declares {@code hurtServer} {@code final}
     * returning a constant {@code false}. Excluding them would mean a hardcoded list that goes
     * stale on the next Minecraft release and ignores modded entities; detecting it from
     * {@code hurtServer}'s return value needs a consecutive-failure counter, because a
     * {@code LivingEntity} returns false during its invulnerability window too. So an operator
     * who types {@code /tplus enemytarget generic minecraft:tnt} gets bots that stand beside lit
     * TNT swinging at something they cannot hurt. Do not "fix" this without reading
     * {@code docs/superpowers/specs/2026-09-14-targeting-non-living-entities-design.md} first.
     */
    public static boolean isTargetable(Entity entity) {
        return entity.isAttackable() && entity.isPickable();
    }
```

- [ ] **Step 4: Switch the `ENTITY` branch to the wide scan and the gate**

Replace the `ENTITY` case body. The existing comment block is kept verbatim and a paragraph is
added for the gate:

```java
            case ENTITY: {
                EnemyTarget enemy = bot.getEnemyTarget();

                if (!enemy.isEmpty()) {
                    for (Entity entity : allEntities(level)) {
                        // `bot != entity` or `generic player` makes every bot target itself and
                        // stand still. Other bots are deliberately not excluded: bots are
                        // ServerPlayers, and naming one with `specific` is the point.
                        //
                        // The fourth argument is `result`, not `null` as the PLAYER branch
                        // passes: there can be several candidates here, so the incumbent has to
                        // be compared against. With a set of one the first candidate meets a
                        // null incumbent anyway and range is skipped, so a single specific
                        // target behaves exactly like PLAYER.
                        //
                        // `matches` is tested before `isTargetable` for selectivity, not for
                        // meaning: this scan now walks dropped items and experience orbs too, and
                        // a set lookup rejects almost all of them before two virtual calls run.
                        if (bot != entity
                                && enemy.matches(entity.getType(), entity.getUUID())
                                && isTargetable(entity)
                                && validateCloserEntity(bot, entity, pos, result)) {
                            result = entity;
                        }
                    }
                }

                break;
            }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew runGameTestServer
```

Expected: BUILD SUCCESSFUL, all four new tests pass, **and every pre-existing test still passes**.
The nine living goals share `validateCloserEntity` with this branch, so a regression there would
show up in `AgentTests` rather than here.

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL — the 109 unit tests are untouched by this change and must stay that
way.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: bots can target anything a player could hit

The ENTITY goal now scans every entity rather than every LivingEntity,
gated on vanilla's own isAttackable() && isPickable(). That is the pair
deciding whether a player's cursor can land on something, so a bot that
respects it targets exactly what a player could.

End crystals, boats, minecarts, item frames, paintings, lead knots and
shulker bullets were all invisible before, and for one reason rather than
seven: none of them is a LivingEntity. Upstream had the same limit via
Bukkit's world.getLivingEntities(). Deviation 36.

Two narrowings come with it, both fixes. Armour-stand markers and
spectators were targetable and cannot be hit by anything, so naming
either was a command that succeeded and did nothing. The spectator case
has a test; the marker case does not, because ArmorStand.setMarker is
private and an access transformer for a test-only need is a worse trade
than leaving one narrowing documented.

The nine living goals keep the narrow scan. Monster, Mob, Raider,
ServerPlayer and Bot are all LivingEntity, so widening them would cost
more and find nothing new.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Make the command layer use the same rule

`BotCommands.enemyTargetSpecific` currently spells "can this be targeted" a second way, and its
comment names boats and item frames as things that cannot be — which stops being an explanation the
moment Task 2 lands.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java:640-670`

**Interfaces:**
- Consumes: `Targeting.isTargetable(Entity)` from Task 2.
- Produces: nothing new.

- [ ] **Step 1: Replace the filter and its comment**

In `enemyTargetSpecific`, replace this:

```java
        // locateTarget returns a LivingEntity and @e matches boats and item frames. Filtering here
        // rather than at scan time means the operator is told, instead of watching a successful
        // command do nothing.
        List<? extends Entity> living =
                selected.stream().filter(e -> e instanceof LivingEntity).toList();

        if (living.isEmpty()) {
```

with this:

```java
        // @e matches anything at all, including entities no attack can touch. Filtering here
        // rather than at scan time means the operator is told, instead of watching a successful
        // command do nothing. Targeting.isTargetable is the same rule the ENTITY scan applies
        // every tick, called rather than restated so the two cannot drift apart.
        List<? extends Entity> targetable =
                selected.stream().filter(Targeting::isTargetable).toList();

        if (targetable.isEmpty()) {
```

Then rename the remaining uses of `living` in the method to `targetable`:

```java
        int ignored = selected.size() - targetable.size();
```

```java
        Set<UUID> ids = targetable.stream().map(Entity::getUUID).collect(Collectors.toUnmodifiableSet());
        String types = targetable.stream()
                .map(e -> EntityType.getKey(e.getType()).getPath())
                .distinct().sorted().collect(Collectors.joining(", "));

        return applyEnemyTarget(ctx, EnemyTarget.ofEntities(ids,
                targetable.size() + " entities (" + types + ")"));
```

- [ ] **Step 2: Fix the imports**

`Targeting` is already imported at `BotCommands.java:44`, so nothing to add. Confirm it:

```bash
grep -n "import net.nuggetmc.tplus.agent.legacy.Targeting;" src/main/java/net/nuggetmc/tplus/command/BotCommands.java
```

Then confirm `LivingEntity` is now unused and remove its import:

```bash
grep -n "LivingEntity" src/main/java/net/nuggetmc/tplus/command/BotCommands.java
```

Expected: no hits at all. If any remain, leave the import.

- [ ] **Step 3: Document the asymmetry at the generic guard**

`enemyTargetGeneric` has an empty-tag guard that exists to stop a target silently matching nothing.
It cannot make the same check for targetability, and the next reader will ask why. Add to the
comment above that guard:

```java
            // An empty tag would set a target that silently matches nothing, which is the failure
            // mode this whole command exists to avoid.
            //
            // There is no matching check that the named type can be hit at all, and there cannot
            // be: isAttackable and isPickable are instance state, and a generic target names a
            // type that may have no instances yet. `generic minecraft:item` is accepted here and
            // refused by the gate at scan time. The `specific` form has no such gap, because it
            // has real entities to test. Recorded in docs/backlog.md.
```

- [ ] **Step 4: Verify it compiles and the suite is still green**

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: BUILD SUCCESSFUL twice, no test count change.

- [ ] **Step 5: Verify the command by hand over RCON**

The command layer has no GameTest coverage, and this is the tier that catches command-tree
problems. Start a dev server and check both the success and the refusal path:

```bash
./gradlew runServer
```

Then, in another terminal, with the RCON password from `run/server.properties` (use `python`, not
`python3`):

```bash
python tools/rcon.py "summon minecraft:end_crystal ~ ~ ~" "tplus enemytarget specific @e[type=end_crystal]"
```

Expected: "Now hunting 1 entities (end_crystal) for N bot(s). Goal set to ENTITY." — where before
this change it answered that none of the selected entities could be targeted.

```bash
python tools/rcon.py "tplus enemytarget specific @e[type=item]"
```

Expected: "None of the N selected entities can be targeted." if only items matched, or an "Ignored
N selected entities" line if the selector caught a mix.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix: enemytarget specific accepts what the scan accepts

The command filtered on `instanceof LivingEntity` and the scan now
filters on isTargetable, so `specific @e[type=end_crystal]` would have
reported that nothing could be targeted while `generic end_crystal`
hunted them. One rule, called rather than restated.

The comment above it had to go with it. It read "locateTarget returns a
LivingEntity and @e matches boats and item frames" -- an accurate
explanation before this branch and a lie after it, and exactly the kind
of comment this project keeps in order to stop the next reader changing
something that only looks wrong.

Also documented, at the guard that cannot have it, why `generic` has no
equivalent check: isAttackable and isPickable are instance state, and a
generic target may name a type with no instances yet.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Redirect Ender Dragon hits to the head

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java:302-312`

**Interfaces:**
- Consumes: `Bot.attack(Entity)` from Task 1.
- Produces: nothing new.

> **Corrected during execution.** The reasoning below is about a *live* dragon and it held, but
> the conclusion did not. A dragon that is spawned, asked about and discarded inside one tick is
> never ticked and never grief-prone, and that test —
> `a_generic_target_finds_the_ender_dragon` — is what caught the gate bug that made this whole
> task unreachable: `isTargetable` rejected the dragon because `EnderDragon.isPickable()` is
> `false`. Write that test as part of Task 2, not Task 4. See deviation 36.

**No automated test, and the reason matters.** A live `EnderDragon` in a GameTest is the same
hazard as a live end crystal — it flies, it has AI, it breaks blocks, and these tests share a level
— and it additionally expects an `EndDragonFight` context that a test structure in the overworld
does not provide. The mechanism is three lines of vanilla arithmetic read straight out of the
sources jar, and the verification is the manual client step below. Do not fabricate a test that
constructs a dragon; it will not work.

- [ ] **Step 1: Add the redirect**

Replace the body of `attack` in `LegacyAgent.java`:

```java
    private void attack(Bot bot, Entity target, Vec3 pos) {
        boolean invincible = target instanceof ServerPlayer player
                && PlayerUtils.isInvincible(player.gameMode());

        if (invincible || target.invulnerableTime >= 5 || pos.distanceTo(target.position()) >= 4) {
            return;
        }

        // The dragon is a Mob, so the scan finds it and all three gates above measure against it,
        // which is right: it is what validateCloserEntity compared and what navigation aims at.
        // Only the recipient of the hit moves.
        //
        // EnderDragon.hurtServer routes to `hurt(level, this.body, ...)`, and that method opens
        // `if (part != this.head) damage = damage / 4 + min(damage, 1)`. A hit worth 8 lands as 3.
        // Vanilla makes players aim at a part -- EnderDragon.isPickable() is false -- and the
        // parts live in ServerLevel's separate dragonParts map rather than the entity index, so
        // widening the scan cannot reach them and redirecting the hit is the only route.
        //
        // Upstream had the same quarter damage. Fixed rather than kept, because the symptom is
        // invisible from outside: bots simply took three times as long and nothing said why.
        // Deviation 37.
        bot.attack(target instanceof EnderDragon dragon ? dragon.head : target);
    }
```

Add the import:

```java
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
```

`dragon.head` is `public final EnderDragonPart` (`EnderDragon.java:68`), so no access transformer
is needed. Confirm before relying on it:

```bash
J=build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar
unzip -p $J net/minecraft/world/entity/boss/enderdragon/EnderDragon.java | grep -n "EnderDragonPart head"
```

Expected: `public final EnderDragonPart head;`

- [ ] **Step 2: Verify the suite is still green**

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: BUILD SUCCESSFUL twice, no test count change. Nothing existing targets a dragon, so this
should be inert everywhere else — and if a test does break, the ternary has changed behaviour for
non-dragons, which it must not.

- [ ] **Step 3: Verify on a real client**

The only tier that can cover this. Start a dev client and server, go to the End, spawn bots with a
weapon and point them at the dragon:

```bash
./gradlew runServer
```

```bash
python tools/rcon.py "tplus create Slayer 3 netherite netherite netherite minecraft:netherite_sword" "tplus enemytarget generic minecraft:ender_dragon"
```

Bots need a player nearby to tick — `/forceload` is not enough. Check `Alive ticks` is advancing in
`/tplus info` before concluding anything. Expected: the dragon's health bar drops roughly three
times faster than on the previous build.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix: bots hit the Ender Dragon's head, not its body

EnderDragon.hurtServer routes to the body part, and EnderDragon.hurt
reduces any non-head hit to `damage / 4 + min(damage, 1)` -- so a swing
worth 8 landed as 3 and bots have been doing a third of a player's damage
since the port began. Upstream did the same thing.

Fixed rather than kept because the symptom is undiscoverable from
outside: bots took three times as long and nothing anywhere said why.

The redirect is the only available route. Vanilla makes players aim at a
part (EnderDragon.isPickable() is false), and the parts live in
ServerLevel's separate dragonParts map rather than in the entity index,
so widening the ENTITY scan in the previous commit does not reach them.
All three attack gates still measure against the dragon itself, which is
what the scan found and what navigation aims at.

No GameTest. A live dragon in a shared test level flies, breaks blocks
and expects an EndDragonFight context an overworld structure has not got
-- the same hazard as letting a test kill an end crystal. Verified on a
client instead, and the arithmetic is in deviation 37.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Documentation

**Files:**
- Modify: `README.md:170-218` (the `Choosing a target` section) and `README.md:281-303`
  (`What is new here`)
- Modify: `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md:10032` (after entry 35)
- Modify: `docs/backlog.md`

**Interfaces:** none — documentation only.

- [ ] **Step 1: Add "what can be targeted" to the README**

In `README.md`, immediately after the paragraph ending "`generic` takes tags too, so
`#minecraft:raiders` works.", insert:

```markdown
**What can be targeted.** Anything a player could hit. That is mobs and players as before, and now
also end crystals, boats, minecarts, item frames, paintings, lead knots and shulker bullets. It is
not dropped items, experience orbs, falling blocks, area-effect clouds, display entities, markers,
armour-stand markers or spectators — none of those can be hit by anything, so a bot ignores them
rather than walking at one forever.

> [!WARNING]
> **An end crystal explodes at power 6, and a bot has to be within 4 blocks to swing.** A squad
> pointed at crystals trades itself for them, one bot per crystal. Bots have no self-preservation
> at all — nothing in the mod reads a bot's health to decide anything — so this is working as
> built, not a bug.
>
> Two entities can be named and never hurt: `minecraft:tnt` and `minecraft:interaction` both look
> attackable to the game and refuse all damage, so bots sent after either will swing at it
> forever.
```

- [ ] **Step 2: Add the widening to "What is new here"**

In the same file, the `/tplus enemytarget` bullet currently ends "There was no way to say 'that
ender dragon'." Replace that bullet with:

```markdown
- **`/tplus enemytarget`.** Upstream could name one player, or a list of mob *types*. There was no
  way to say "that ender dragon".
- **Targets that are not alive.** Upstream's targeting was typed on living entities, so end
  crystals, boats, minecarts, item frames and paintings could not be hunted at all. A bot now
  targets whatever a player could hit. Ender dragons also take full damage rather than a quarter,
  because bots aim at the head like everyone else.
```

- [ ] **Step 3: Append entries 36 and 37 to Plan B's deviation register**

In `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md`, insert between the end of entry 35
and the line `And two things found in Plan D that are **not** deviations:`:

```markdown
36. **Targeting is typed on `Entity`, not `LivingEntity`.** Upstream's `locateTarget` returned a
    Bukkit `LivingEntity` scanned from `world.getLivingEntities()`, so end crystals, boats,
    minecarts, item frames, paintings, lead knots and shulker bullets were invisible to every goal
    — one cause, not seven — and `/tplus enemytarget specific` answered that they could not be
    targeted. The pipeline is now `Entity` throughout, and the `ENTITY` goal scans every entity
    gated on vanilla's own `isAttackable() && isPickable()`, the pair that decides whether a
    player's cursor can land on something. The nine other goals keep the living scan: `Monster`,
    `Mob`, `Raider`, `ServerPlayer` and `Bot` are all `LivingEntity`, so widening them would cost
    more and find nothing new.

    The widening was nearly free because the agent barely touches its target. Every member read on
    it anywhere — `position`, `getEyePosition`, `isAlive`, `level`, `hurtServer` and
    `invulnerableTime` — is declared on `Entity`, including the last two, which look like
    `LivingEntity` members and are not. Eleven signatures moved and no method body did.

    Three consequences. **Two narrowings, both fixes:** armour-stand markers and spectators were
    targetable and can be hit by nothing, so naming either was a command that succeeded and did
    nothing; the spectator case is GameTested, the marker case is not, because
    `ArmorStand.setMarker` is private and an access transformer for a test-only need is the worse
    trade. **`TerminatorLocateTargetEvent.getTarget()` changed type** — both in-repo listeners
    survive unmodified, since `RetargetHandler` only passes a `LivingEntity` into `setTarget`, and
    the breaking direction is an addon assigning `getTarget()` to a `LivingEntity`. **`PrimedTnt`
    and `Interaction` pass the gate and can never be damaged**, each declaring `hurtServer` final
    returning false; accepted rather than excluded, because a hardcoded list goes stale every
    release and ignores modded entities. Designed in
    `docs/superpowers/specs/2026-09-14-targeting-non-living-entities-design.md`.
37. **Bots hit the Ender Dragon's head.** `EnderDragon.hurtServer` routes to
    `hurt(level, this.body, …)`, which opens `if (part != this.head) damage = damage / 4 +
    Math.min(damage, 1)`. A hit worth 8 landed as 3, so bots did a third of a player's damage for
    no reason discoverable from outside the code. `LegacyAgent.attack` now passes `dragon.head` to
    `bot.attack`; all three gates still measure against the dragon itself, which is what the scan
    found and what navigation aims at. Widening the scan cannot substitute for this — vanilla makes
    players aim at a part, `EnderDragon.isPickable()` is false, and `EnderDragonPart`s live in
    `ServerLevel`'s separate `dragonParts` map rather than the entity index. Upstream had the
    identical behaviour. No GameTest: a live dragon in a shared test level flies, breaks blocks and
    wants an `EndDragonFight` context, which is the same hazard that keeps the crystal test from
    letting a bot swing.
```

- [ ] **Step 4: Add the backlog entry**

In `docs/backlog.md`, under `## Features upstream never had`, append a new subsection at the end of
the file:

```markdown
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
```

- [ ] **Step 5: Check the README renders and its links still resolve**

```bash
grep -n "What can be targeted\|Targets that are not alive" README.md
```

Expected: both hits. The `## Contents` list at the top links to section headings only, and neither
insertion adds a heading, so no link needs updating — confirm nothing above `## Contents` changed.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
docs: register the Entity widening and the dragon head

Deviations 36 and 37, extending Plan B's register rather than starting a
list. 36 carries the three consequences that a diff will not show: the
two narrowings (armour-stand markers and spectators, both fixes, one of
them untested because ArmorStand.setMarker is private), the event's type
change and why both in-repo listeners survive it, and PrimedTnt and
Interaction passing a gate they can never satisfy.

The README gains what can and cannot be targeted, and both ways it bites
an operator: an end crystal explodes at power 6 inside the 4-block range
a bot needs to swing, and TNT and interaction entities can be named but
never hurt. Stated as working-as-built, because bots have no
self-preservation and nothing here changes that.

Backlog gains the one real gap: `generic` cannot check targetability at
command time the way `specific` can.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Version bump and changelog

**Files:**
- Modify: `gradle.properties:14`
- Modify: `README.md:13` (badge), `README.md:35` (status), `README.md:66` (install filename)
- Create: `CHANGELOG.md`

**Interfaces:**
- Produces: `mod_version=5.1.0-ALPHA`, which Task 7's workflow reads and checks the tag against.
  `CHANGELOG.md` with a `## v5.1.0-ALPHA` heading, which Task 7's workflow extracts.

- [ ] **Step 1: Bump the version**

In `gradle.properties`:

```properties
mod_version=5.1.0-ALPHA
```

- [ ] **Step 2: Update the three version strings in the README**

Line 13, the badge:

```markdown
[![Version](https://img.shields.io/badge/version-5.1.0--ALPHA-DFB317?style=flat-square)](#status)
```

Line 35, the status block:

```markdown
> **Status: 5.1.0-ALPHA.** The agent works and has been played against. The public API module and
> the neural-network AI are not ported yet — see [What is not built yet](#what-is-not-built-yet).
```

Line 66, the install step — and add the Releases link, which is only honest once a release exists:

```markdown
1. Download `tplus-5.1.0-ALPHA.jar` from [Releases](https://github.com/ItzBloxy/TerminatorPlusPlus/releases)
   and drop it into `mods/`.
```

- [ ] **Step 3: Write `CHANGELOG.md`**

Create it at the repository root. The `## v5.1.0-ALPHA` heading must match the tag exactly — Task
7's workflow extracts by that string and fails if it is missing.

```markdown
# Changelog

Notable changes per release. The full history is in the commit log; this is what an operator
upgrading would want to know.

## v5.1.0-ALPHA

**Bots can target anything a player could hit.** End crystals, boats, minecarts, item frames,
paintings, lead knots and shulker bullets were all invisible to every goal, because targeting was
typed on living entities. `/tplus enemytarget generic minecraft:end_crystal` now works.

Two warnings come with it. An end crystal explodes at power 6 and a bot must be within 4 blocks to
swing, so a squad pointed at crystals trades itself for them — bots have no self-preservation.
And `minecraft:tnt` and `minecraft:interaction` can be named but never hurt, so bots sent after
either will swing at it forever.

**Bots hit the Ender Dragon's head.** They were hitting the body, which vanilla reduces to
`damage / 4 + min(damage, 1)` — a third of a player's damage, for no reason visible from outside.

**Bots mine leaves with shears.** Eight ticks a block instead of 120, and wool and cobweb come
along with them. Shears are untiered and always present.

**Bots walk instead of jumping under a low ceiling.** A tunnelling bot bounced off the ceiling and
barely mined; it now walks. Measured 17% faster overall, and far more than that in a tight
corridor — 17 blocks to a jumping bot's 2 over 700 ticks.

**`/tplus descendrange`.** A stuck bot would tunnel straight down from any distance, then mine
across. The descent is now capped on horizontal distance, default 8, and `unlimited` restores the
old behaviour.

**Mining sounds match vanilla.** Progress played a full block-break sound every two ticks at a flat
volume. It is now vanilla's hit sound, every four ticks, at vanilla's volume and pitch — a tap
rather than continuous demolition.

## ALPHA

First release. The NeoForge port of TerminatorPlus 4.5.1-BETA: the agent, the environment commands,
loadouts and entity targeting.
```

- [ ] **Step 4: Verify the build picks up the new version**

```bash
./gradlew jar && ls build/libs/
```

Expected: `tplus-5.1.0-ALPHA.jar` exists. The old `tplus-5.0.0-ALPHA.jar` may still be sitting
there from an earlier build — that is fine and is not what the workflow will attach, but confirm
the new one is present and freshly timestamped.

- [ ] **Step 5: Verify no version string was missed**

```bash
grep -rn "5\.0\.0-ALPHA" --include="*.md" --include="*.properties" --include="*.yml" . | grep -v "^./build/"
```

Expected: no hits at all. The previous release was tagged bare `ALPHA`, so `CHANGELOG.md` never
names the old version either. Hits under `build/` are stale artifacts and are filtered out.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
chore: 5.1.0-ALPHA, and a changelog to release it from

Five user-visible changes since the ALPHA tag -- shears on leaves,
/tplus descendrange, the walk gear, the mining sound fix and non-living
targets -- which is a minor bump rather than a patch.

CHANGELOG.md is new, and exists because the release workflow needs
hand-written notes to read: --generate-notes would publish 22 commit
subjects, fourteen of them starting with `docs:`. The `## v5.1.0-ALPHA`
heading is load-bearing -- the workflow extracts by that exact string and
fails the release if it is missing.

Install now points at the Releases page, which is honest for the first
time as of the next commit.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: The release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: `mod_version=5.1.0-ALPHA` and the `## v5.1.0-ALPHA` heading, both from Task 6.
- Produces: a GitHub release when a `v*` tag is pushed.

**The workflow must be on the branch before the tag is pushed.** GitHub runs the workflow as it
exists at the tagged commit, so a tag pushed first does nothing at all. **Do not push the tag as
part of this task** — that publishes a public release with a downloadable jar, and it needs the
user to ask for it explicitly.

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/release.yml`:

```yaml
name: "Release"

# Tags only -- never a branch push and never a manual dispatch. The tag is what names the release
# and what the built jar is checked against, so a run without one has nothing to verify.
on:
  push:
    tags:
      - "v*"

# gh release create writes to the repository. Nothing else in this workflow needs a token.
permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    # Same ceiling as compile.yml: the GameTest step boots a Minecraft server, and a hung one
    # should fail in minutes rather than sit on a runner until the six-hour default expires.
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 25

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v6
        with:
          # "basic" is the MIT-licensed actions/cache implementation. v6 defaults to "enhanced",
          # which loads gradle-actions-caching -- closed source, under Gradle Technologies' own
          # terms of use rather than the MIT licence covering the rest of the action. compile.yml
          # opted out of that deliberately; a second workflow must not reintroduce it by omission.
          cache-provider: basic

      # Without this, pushing v5.1.0-ALPHA while gradle.properties still reads 5.0.0-ALPHA builds
      # tplus-5.0.0-ALPHA.jar and attaches it to a release called v5.1.0-ALPHA.
      - name: Check the tag matches mod_version
        run: |
          tag="${GITHUB_REF_NAME#v}"
          version="$(grep -E '^mod_version=' gradle.properties | cut -d= -f2)"
          if [ "$tag" != "$version" ]; then
            echo "Tag $GITHUB_REF_NAME implies version '$tag', but gradle.properties says '$version'." >&2
            exit 1
          fi
          echo "version=$version" >> "$GITHUB_ENV"

      # Fails the release rather than publishing one with empty notes.
      - name: Extract the changelog section for this tag
        run: |
          awk -v tag="## $GITHUB_REF_NAME" '
            $0 == tag { found = 1; next }
            found && /^## / { exit }
            found { print }
          ' CHANGELOG.md > release-notes.md
          if [ ! -s release-notes.md ] || [ -z "$(tr -d '[:space:]' < release-notes.md)" ]; then
            echo "CHANGELOG.md has no '## $GITHUB_REF_NAME' section, or it is empty." >&2
            exit 1
          fi

      - name: Execute Gradle build
        run: ./gradlew build

      # Before the release is created, so a failing test means no release exists at all -- the
      # same order and the same reason as compile.yml's artifact upload.
      - name: Run the in-world GameTests
        run: ./gradlew runGameTestServer

      - name: Create the release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          # A version carrying a suffix -- 5.1.0-ALPHA, 5.2.0-BETA, 5.2.0-rc1 -- is a prerelease.
          case "$version" in
            *-*) prerelease="--prerelease" ;;
            *)   prerelease="" ;;
          esac

          gh release create "$GITHUB_REF_NAME" \
            --title "$GITHUB_REF_NAME" \
            --notes-file release-notes.md \
            $prerelease \
            "build/libs/tplus-$version.jar"
```

`gh` is preinstalled on GitHub-hosted runners, so no third-party action is added to a repository
that has already declined a closed-source CI component.

- [ ] **Step 2: Test the version guard locally**

The two shell steps are the parts most likely to be wrong, and both can be run here without CI.
Confirm the matching case passes:

```bash
GITHUB_REF_NAME=v5.1.0-ALPHA bash -c 'tag="${GITHUB_REF_NAME#v}"; version="$(grep -E "^mod_version=" gradle.properties | cut -d= -f2)"; if [ "$tag" != "$version" ]; then echo "MISMATCH: tag=$tag version=$version"; exit 1; fi; echo "OK: $version"'
```

Expected: `OK: 5.1.0-ALPHA`

And that a mismatched tag is caught:

```bash
GITHUB_REF_NAME=v9.9.9 bash -c 'tag="${GITHUB_REF_NAME#v}"; version="$(grep -E "^mod_version=" gradle.properties | cut -d= -f2)"; if [ "$tag" != "$version" ]; then echo "MISMATCH: tag=$tag version=$version"; exit 1; fi; echo "OK"'
```

Expected: `MISMATCH: tag=9.9.9 version=5.1.0-ALPHA` and exit status 1.

- [ ] **Step 3: Test the changelog extraction locally**

```bash
GITHUB_REF_NAME=v5.1.0-ALPHA bash -c 'awk -v tag="## $GITHUB_REF_NAME" '"'"'$0 == tag { found = 1; next } found && /^## / { exit } found { print }'"'"' CHANGELOG.md'
```

Expected: the v5.1.0-ALPHA body only — starting with the "Bots can target anything" paragraph and
stopping before `## ALPHA`. Read the output and confirm it does **not** include the `## ALPHA`
section.

Then confirm a missing section is caught:

```bash
GITHUB_REF_NAME=v0.0.0 bash -c 'awk -v tag="## $GITHUB_REF_NAME" '"'"'$0 == tag { found = 1; next } found && /^## / { exit } found { print }'"'"' CHANGELOG.md | tr -d "[:space:]" | wc -c'
```

Expected: `0` — which is what the workflow's emptiness check keys on.

- [ ] **Step 4: Check the workflow is valid YAML**

```bash
python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('valid')"
```

Expected: `valid`. If PyYAML is not installed, skip this — GitHub validates on push, and Step 5
will catch a syntax error before any tag is involved.

- [ ] **Step 5: Commit and push the branch, but not a tag**

```bash
git add .github/workflows/release.yml
git commit -m "$(cat <<'EOF'
ci: publish a release when a v* tag is pushed

Builds on a clean runner, runs the unit tests and the in-world GameTests,
and only then creates the release -- so a failing test means no release
exists rather than an untested jar attached to one. Same order and same
reason as compile.yml's artifact upload.

Two guards. The tag must match mod_version, because pushing v5.1.0-ALPHA
against a stale gradle.properties would attach tplus-5.0.0-ALPHA.jar to a
release named v5.1.0-ALPHA and nothing would complain. And the notes come
from the CHANGELOG.md section matching the tag, with the job failing if
that section is missing, rather than from --generate-notes.

Publishes with gh, which is preinstalled on GitHub runners, rather than a
third-party action -- this repository already declined a closed-source
cache component in compile.yml, and cache-provider: basic is carried over
here for the same reason rather than left to the v6 default.

The workflow has to exist at the tagged commit to run at all, so it lands
before any tag is pushed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin master
```

- [ ] **Step 6: Stop and ask before tagging**

Do not run `git tag` or `git push --tags`. Report to the user that everything is committed and
pushed, that the release will be cut by:

```bash
git tag -a v5.1.0-ALPHA -m "Terminator++ 5.1.0-ALPHA" && git push origin v5.1.0-ALPHA
```

and ask whether to run it. Pushing that tag publishes a public release with a downloadable jar;
it is the user's call, not an inference from this plan.

---

## Verification checklist

Before reporting the work complete, confirm each of these by running it — not by remembering it:

- [ ] `./gradlew build` — BUILD SUCCESSFUL, 109 unit tests pass
- [ ] `./gradlew runGameTestServer` — BUILD SUCCESSFUL, test count is the original plus **four**
- [ ] `./gradlew jar` — produces `build/libs/tplus-5.1.0-ALPHA.jar`
- [ ] `grep -rn "5\.0\.0-ALPHA" --include="*.md" --include="*.properties" . | grep -v "^./build/"`
      — no hits
- [ ] `grep -rn "instanceof LivingEntity" src/main` — no hits
- [ ] The RCON checks in Task 3 Step 5 both behaved as described
- [ ] The manual dragon check in Task 4 Step 3 was actually performed on a client, or is reported
      as not performed — not silently skipped
- [ ] Deviations 36 and 37 exist in Plan B's register, and no new register was started
- [ ] No tag has been pushed
