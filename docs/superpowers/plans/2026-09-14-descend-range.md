# Descend Range Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a stuck bot from tunnelling down toward a target that is still far away horizontally, so it closes the horizontal gap first and only descends once it is near.

**Architecture:** One boolean changes. `Navigation.checkDown`'s first branch is gated on a merged `withinTargetXZ || sameXZ` flag today, and only the first half of that is about distance — `sameXZ` is a stuckness sample. The branch gains a horizontal bound on the stuckness half only, sourced from a new runtime setting. Nothing else moves: the sideways behaviour a refused bot falls through to already exists in `checkSide`.

**Tech Stack:** Java 21, NeoForge for Minecraft 26.2, JUnit 5 (`src/test`), NeoForge test framework GameTests (`src/gametest`), Brigadier commands, Gradle.

## Global Constraints

- **Fidelity is the point.** Method bodies are translated from the Paper plugin, not redesigned. Every deliberate divergence goes in the deviation register in `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` — do not start a new list. This change is **deviation 33**.
- The faithfulness oracle is the `paper-original` branch. Read the original of any file with `git show paper-original:<path>`. **Never modify that branch.**
- Comments explain *why*, especially where the code looks wrong. When a line diverges from upstream, say what upstream did and what changed, at the line that changed.
- Verify vanilla signatures against `build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar`, never a Paper jar.
- `@TestHolder` is **required** on every GameTest. Without it the test is silently unregistered and the suite still reports success.
- GameTests share a level and a JVM. Assert the rule, never that the level is empty, and clear static state in a `finally`.
- Prefer a direct call to ticking and waiting. `move()` adds `Math.random()` to every jump, so a test that runs 200 ticks and asserts on position measures the walk, not the decision.
- The spec is `docs/superpowers/specs/2026-09-14-descend-range-design.md`. Read it before Task 1.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java` | Modify | The rule. New static `mayDigDown` predicate; `checkDown` takes the two flags and a range instead of one merged flag. |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java` | Modify | Holds `descendRange` and the `DESCEND_RANGE_UNLIMITED` sentinel; passes them at the `checkDown` call site. |
| `src/main/java/net/nuggetmc/tplus/command/BotCommands.java` | Modify | `/tplus descendrange` — set, `unlimited`, and report. |
| `src/test/java/net/nuggetmc/tplus/agent/legacy/NavigationTest.java` | **Create** | Unit coverage of `mayDigDown`. No world needed. |
| `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java` | Modify | One in-world test pinning the cap, as a pair of assertions on one setup. |
| `README.md` | Modify | The command reference. |
| `docs/backlog.md` | Modify | The pathfinding-visualiser section's claim about descent. |
| `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` | Modify | Deviation 33. |

---

### Task 1: The rule

**Files:**
- Create: `src/test/java/net/nuggetmc/tplus/agent/legacy/NavigationTest.java`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java:156-188`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java:54-55` and `:179-212`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `public static boolean Navigation.mayDigDown(boolean withinTargetXZ, boolean sameXZ, double horizontal, int range)`
  - `public boolean Navigation.checkDown(Bot bot, Vec3 targetPos, boolean withinTargetXZ, boolean sameXZ, int range)` — replaces the three-argument form
  - `public static final int LegacyAgent.DESCEND_RANGE_UNLIMITED` (= `Integer.MAX_VALUE`)
  - `public int LegacyAgent.descendRange` (= `8`)

---

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/net/nuggetmc/tplus/agent/legacy/NavigationTest.java`:

```java
package net.nuggetmc.tplus.agent.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure: the horizontal gate on {@code checkDown}'s first branch.
 *
 * <p>The whole of this port's descent change is one boolean expression, and it is deliberately
 * shaped so it can be tested without a world — no level, no bot, no {@code ItemStack}. The
 * arguments are exactly what {@code tickBot} already computes.
 */
class NavigationTest {

    private static final int RANGE = 8;

    @Test
    void aBotBesideItsTargetDigsWithoutBeingStuck() {
        assertTrue(Navigation.mayDigDown(true, false, 2, RANGE));
    }

    @Test
    void aBotBesideItsTargetDigsWhileStuckToo() {
        assertTrue(Navigation.mayDigDown(true, true, 2, RANGE));
    }

    @Test
    void aStuckBotInsideTheRangeDigs() {
        assertTrue(Navigation.mayDigDown(false, true, 5, RANGE));
    }

    @Test
    void aStuckBotOutsideTheRangeDoesNot() {
        // The whole point of the change. Upstream dug here, at any distance at all.
        assertFalse(Navigation.mayDigDown(false, true, 20, RANGE));
    }

    @Test
    void aDistantBotThatIsNotStuckNeverDug() {
        // Upstream's behaviour too -- this branch always needed one of the two flags.
        assertFalse(Navigation.mayDigDown(false, false, 20, RANGE));
    }

    @Test
    void withinTargetXZBypassesTheRangeEntirely() {
        // The case that earns its keep. withinTargetXZ is a 7x7 box around the AIM POINT, and
        // the aim point sits up to 3 blocks from the target (BotMath.circleOffset), so it admits
        // bots up to 8.66 blocks from the target itself. Collapsing this expression to
        // `horizontal < range` would veto bots the rest of tickBot already treats as adjacent.
        assertTrue(Navigation.mayDigDown(true, true, 8.5, RANGE));
    }

    @Test
    void unlimitedRestoresUpstreamsBehaviour() {
        assertTrue(Navigation.mayDigDown(false, true, 500,
                LegacyAgent.DESCEND_RANGE_UNLIMITED));
    }

    @Test
    void aRangeOfZeroStopsStuckBotsDescendingAtAll() {
        // Coherent rather than degenerate: the far end of the same dial. Only withinTargetXZ
        // bots descend, and they are not consulting the range.
        assertFalse(Navigation.mayDigDown(false, true, 0.5, 0));
        assertTrue(Navigation.mayDigDown(true, true, 0.5, 0));
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

```bash
./gradlew test --tests "net.nuggetmc.tplus.agent.legacy.NavigationTest"
```

Expected: compilation failure — `cannot find symbol: method mayDigDown` and `cannot find symbol: variable DESCEND_RANGE_UNLIMITED`.

- [ ] **Step 3: Add the two fields to `LegacyAgent`**

In `src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java`, replace:

```java
    /** Whether bots aim at a ring around the target rather than the target itself. */
    public boolean offsets = true;
```

with:

```java
    /** {@link #descendRange}'s "no cap", which is upstream's behaviour. */
    public static final int DESCEND_RANGE_UNLIMITED = Integer.MAX_VALUE;

    /** Whether bots aim at a ring around the target rather than the target itself. */
    public boolean offsets = true;

    /**
     * How close a stuck bot must be, horizontally, before it tunnels down toward a target below
     * it. Upstream had no such bound — see {@link Navigation#mayDigDown}.
     */
    public int descendRange = 8;
```

- [ ] **Step 4: Add `mayDigDown` to `Navigation`**

In `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java`, insert this immediately **above** the `checkDown` javadoc (currently line 156):

```java
    /**
     * Whether the bot is close enough, horizontally, to start digging toward a target below it.
     *
     * <p>This bound is new; upstream had none. {@code tickBot} passed {@code checkDown} one
     * merged flag, {@code withinTargetXZ || sameXZ}, and only the first half of that is about
     * distance — {@code sameXZ} is {@code center}'s stuckness sample, which says nothing about
     * how far away the target is. So a bot fifty blocks out that stopped moving for one second
     * dug straight down to the target's Y and mined across at the bottom, and digging kept it in
     * the same column, so the flag stayed true the whole way down.
     *
     * <p>The {@code withinTargetXZ} term bypasses the range deliberately, and is <b>not</b>
     * redundant: it is a 7x7 box around the <i>aim point</i>, and the aim point sits up to 3
     * blocks from the target ({@link net.nuggetmc.tplus.motion.BotMath#circleOffset}), so it
     * admits bots up to 8.66 blocks out. Collapsing this to {@code horizontal < range} would
     * start vetoing bots the rest of {@code tickBot} already treats as adjacent.
     *
     * @param range {@link LegacyAgent#DESCEND_RANGE_UNLIMITED} restores upstream's behaviour
     */
    public static boolean mayDigDown(boolean withinTargetXZ, boolean sameXZ, double horizontal,
                                     int range) {
        return withinTargetXZ || (sameXZ && horizontal < range);
    }

```

- [ ] **Step 5: Run the unit tests to verify they pass**

```bash
./gradlew test --tests "net.nuggetmc.tplus.agent.legacy.NavigationTest"
```

Expected: 8 tests, all PASS.

- [ ] **Step 6: Rewrite `checkDown` to use it**

In `Navigation.java`, replace the whole javadoc and signature down to the end of the first branch. Replace this:

```java
    /**
     * Digs downward when the target is below and out of sight.
     *
     * <p>Ported from {@code checkDown}. Two ways in: the bot is in the same column and more than
     * one block above the target, or it is more than ten blocks above and within ten
     * horizontally. Either way it mines the block it is standing on.
     *
     * <p>The second way is upstream's {@code else}, not a second {@code if}: a bot that satisfies
     * the first test but is standing on nothing gives up here rather than falling through to it.
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

        // Upstream zeroed the Y of both locations and measured the distance between them, which
        // is this.
        double horizontal = Math.hypot(targetPos.x - pos.x, targetPos.z - pos.z);

        if (BotMath.floorY(pos) > BotMath.floorY(targetPos) + 10 && horizontal < 10) {
```

with this:

```java
    /**
     * Digs downward when the target is below and out of sight.
     *
     * <p>Ported from {@code checkDown}. Two ways in: the bot is close enough horizontally and
     * more than one block above the target, or it is more than ten blocks above and within ten
     * horizontally. Either way it mines the block it is standing on.
     *
     * <p>The second way is upstream's {@code else}, not a second {@code if}: a bot that satisfies
     * the first test but is standing on nothing gives up here rather than falling through to it.
     *
     * <p>"Close enough horizontally" is {@link #mayDigDown} and is the one divergence in this
     * method. Upstream took a single merged flag here and named the parameter
     * {@code sameColumn} — a misnomer that is most of why the unbounded descent read as
     * intentional for so long. The flag was {@code withinTargetXZ || sameXZ}: a 7x7 box around
     * the aim point, or a stopwatch. Neither of them is a column.
     *
     * @param range how close, horizontally, a stuck bot must be before it may dig;
     *              {@link LegacyAgent#DESCEND_RANGE_UNLIMITED} is upstream's behaviour
     * @return true when the bot is now mining, meaning {@code tickBot} must stop here
     */
    public boolean checkDown(Bot bot, Vec3 targetPos, boolean withinTargetXZ, boolean sameXZ,
                             int range) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();

        // Either ray reaching the target means there is no need to dig.
        if (LegacyUtils.checkFreeSpace(level, pos, targetPos)
                || LegacyUtils.checkFreeSpace(level, bot.getEyePosition(), targetPos)) {
            return false;
        }

        List<BlockPos> standing = bot.getStandingOn();

        // Upstream zeroed the Y of both locations and measured the distance between them, which
        // is this. It sat below the first branch, which had no use for it; the first branch now
        // does, so it moves up. Pure arithmetic on two locals, so the move changes nothing.
        double horizontal = Math.hypot(targetPos.x - pos.x, targetPos.z - pos.z);

        if (mayDigDown(withinTargetXZ, sameXZ, horizontal, range)
                && BotMath.floorY(pos) > BotMath.floorY(targetPos) + 1) {
            if (standing.isEmpty()) {
                return false;
            }

            mineBelow(bot, standing.get(0));
            return true;
        }

        if (BotMath.floorY(pos) > BotMath.floorY(targetPos) + 10 && horizontal < 10) {
```

Leave everything after that line exactly as it is. The second branch is untouched — it keeps its own `horizontal < 10`.

- [ ] **Step 7: Update the `checkDown` call site in `tickBot`**

In `LegacyAgent.java`, replace:

```java
            if (navigation.checkDown(bot, livingTarget.position(), bothXZ)) {
                return;
            }
```

with:

```java
            if (navigation.checkDown(bot, livingTarget.position(), withinTargetXZ, sameXZ,
                    descendRange)) {
                return;
            }
```

- [ ] **Step 8: Correct the now-stale comment above `bothXZ`**

Still in `LegacyAgent.java`, `bothXZ`'s comment claims `checkDown` reads it, which stopped being true in Step 7. Replace:

```java
            // Upstream wrote this expression three times: once as a variable for checkDown,
            // once inline as checkUp's guard, and once again for checkSide in task 21. Kept as
            // three readings rather than one, because collapsing them would hide that the same
            // condition is being asked for three different reasons.
            boolean bothXZ = withinTargetXZ || sameXZ;
```

with:

```java
            // Upstream wrote this expression three times: once as a variable for checkDown,
            // once inline as checkUp's guard, and once again for checkSide in task 21. Kept as
            // separate readings rather than one, because collapsing them would hide that the
            // same condition is being asked for different reasons.
            //
            // checkDown is no longer one of them. It takes the two flags apart, because the
            // horizontal cap applies to sameXZ and must not apply to withinTargetXZ —
            // see Navigation.mayDigDown.
            boolean bothXZ = withinTargetXZ || sameXZ;
```

- [ ] **Step 9: Build and run the whole unit suite**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. The unit count goes from 101 to 109 (eight new tests in `NavigationTest`). No existing test calls `checkDown` directly, so nothing else needs a signature fix.

- [ ] **Step 10: Run the GameTests to confirm nothing regressed**

```bash
./gradlew runGameTestServer
```

Expected: all 147 pass — the suite is 147, not the 146 CLAUDE.md claims; that line is stale by one
and Task 4 corrects it. In particular `a_bot_mines_down_toward_a_target_far_below` must still pass — its bot sits at `(4.5, 15, 4.5)` and its target at `(4.5, 1, 4.5)`, a horizontal distance of 0, and it satisfies `withinTargetXZ` besides, so it never consults the cap.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java src/main/java/net/nuggetmc/tplus/agent/legacy/LegacyAgent.java src/test/java/net/nuggetmc/tplus/agent/legacy/NavigationTest.java
git commit -m "feat: cap how far a stuck bot will tunnel down

checkDown's first branch was gated on `withinTargetXZ || sameXZ` and
bounded horizontally only through the first of those. sameXZ is center()'s
stuckness sample -- it says nothing about distance -- so a bot fifty
blocks out that bumped into a hill for one second dug straight to the
target's Y and then mined across. Digging kept it in the same column, so
the flag stayed true the whole way down.

The branch now asks withinTargetXZ || (sameXZ && horizontal < range).
Branch two already had its own bound and is untouched.

The cap deliberately does not apply to withinTargetXZ: that is a 7x7 box
around the aim point, and circleOffset puts the aim point up to 3 blocks
from the target, so it admits bots up to 8.66 blocks out. A flat guard
would behave identically at any range of 9 or more and start vetoing
genuinely-near bots below that.

LegacyAgent.descendRange defaults to 8; DESCEND_RANGE_UNLIMITED restores
upstream's behaviour. Deviation 33.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: The `/tplus descendrange` command

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java` — register near line 216, handlers near line 953

**Interfaces:**
- Consumes: `LegacyAgent.descendRange`, `LegacyAgent.DESCEND_RANGE_UNLIMITED` (Task 1); the existing `private static LegacyAgent legacyAgent(CommandContext<CommandSourceStack> ctx)` helper, which sends "No legacy agent is installed." and returns null.
- Produces: `/tplus descendrange <blocks>`, `/tplus descendrange unlimited`, `/tplus descendrange`.

No new imports. `IntegerArgumentType`, `Commands`, `Component`, `CommandContext` and `CommandSourceStack` are all already imported in this file.

---

- [ ] **Step 1: Register the command**

In `BotCommands.java`, immediately after the `offsets` block (which ends `})));` just before the `// Seven arguments in the worst case` comment), insert:

```java
        // Not the offsets block's inline `instanceof LegacyAgent`, which silently succeeds when
        // no legacy agent is installed. That is harmless for a write-only toggle and wrong for a
        // command that also reports -- it would print a number governing nothing.
        root.then(Commands.literal("descendrange")
                .executes(BotCommands::showDescendRange)
                .then(Commands.literal("unlimited")
                        .executes(ctx -> setDescendRange(ctx, LegacyAgent.DESCEND_RANGE_UNLIMITED)))
                .then(Commands.argument("blocks", IntegerArgumentType.integer(0))
                        .executes(ctx -> setDescendRange(ctx,
                                IntegerArgumentType.getInteger(ctx, "blocks")))));
```

A literal and an integer argument as siblings raise no Brigadier ambiguity — `unlimited` cannot parse as an integer. Step 6 checks the server log for that warning anyway.

- [ ] **Step 2: Add the two handlers**

In `BotCommands.java`, immediately **above** the `private static LegacyAgent legacyAgent(...)` helper, insert:

```java
    /**
     * Reports the descent range.
     *
     * <p>A range of 0 is a real setting rather than a degenerate one: {@code horizontal < 0}
     * never holds, so stuck bots stop descending entirely and only bots already beside their
     * target dig down. It is the far end of the same dial and needs no special case.
     */
    private static int showDescendRange(CommandContext<CommandSourceStack> ctx) {
        LegacyAgent agent = legacyAgent(ctx);

        if (agent == null) {
            return 0;
        }

        int range = agent.descendRange;
        String text = range == LegacyAgent.DESCEND_RANGE_UNLIMITED
                ? "Descent range is unlimited — bots tunnel down toward a target at any distance."
                : "Descent range is " + range + " blocks.";

        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int setDescendRange(CommandContext<CommandSourceStack> ctx, int range) {
        LegacyAgent agent = legacyAgent(ctx);

        if (agent == null) {
            return 0;
        }

        agent.descendRange = range;

        String text = range == LegacyAgent.DESCEND_RANGE_UNLIMITED
                ? "Descent range set to unlimited"
                : "Descent range set to " + range + " blocks";

        ctx.getSource().sendSuccess(() -> Component.literal(text), true);
        return 1;
    }

```

- [ ] **Step 3: Build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. If `LegacyAgent` is not resolvable, check it is imported — it already is, for the `offsets` handler.

- [ ] **Step 4: Start a dev server**

```bash
./gradlew runServer
```

Leave it running. If it dies with an opaque `IOException` about the session lock, a previous `runServer` is still alive — kill it first.

- [ ] **Step 5: Exercise the command tree over RCON**

In a second shell. Invoke it as `python`, **not** `python3` — `python3` on this machine is the Microsoft Store placeholder and exits without running anything.

```bash
python tools/rcon.py "tplus descendrange" "tplus descendrange 12" "tplus descendrange" "tplus descendrange unlimited" "tplus descendrange" "tplus descendrange 0" "tplus descendrange 8"
```

Expected replies, in order:

```
Descent range is 8 blocks.
Descent range set to 12 blocks
Descent range is 12 blocks.
Descent range set to unlimited
Descent range is unlimited — bots tunnel down toward a target at any distance.
Descent range set to 0 blocks
Descent range set to 8 blocks
```

Then check a bad argument is rejected by Brigadier rather than by the handler:

```bash
python tools/rcon.py "tplus descendrange -1"
```

Expected: a parse error naming the minimum — "Integer must not be less than 0, found -1" — not a
success message and not a silent clamp. The floor belongs to the argument type, so the handlers
never see a negative.

- [ ] **Step 6: Check the server log for a Brigadier ambiguity warning**

```bash
grep -i "ambiguity\|ambiguous" run/logs/latest.log
```

Expected: no hits mentioning `descendrange`. Plan C hit a real ambiguity on `/tplus environment addsolid` and restructured the tree for it, so this check is not theatre.

- [ ] **Step 7: Stop the server**

```bash
python tools/rcon.py "stop"
```

`stop` never replies — the server closes the socket, so a close or timeout is success.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/command/BotCommands.java
git commit -m "feat: add /tplus descendrange

Sets the horizontal cap on tunnelling down, reports it with no argument,
and lifts it entirely with `unlimited` -- which restores upstream's
behaviour without a rebuild, so 8, 10 and unbounded can be compared in
one session.

Uses legacyAgent(ctx) rather than the offsets block's inline instanceof.
That one silently succeeds when no legacy agent is installed, which is
harmless for a write-only toggle and wrong for a command that reports:
it would print a number governing nothing.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: The in-world test

**Files:**
- Modify: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java` — add one test after `a_bot_mines_down_toward_a_target_far_below`

**Interfaces:**
- Consumes: `Navigation.checkDown(Bot, Vec3, boolean, boolean, int)` and `LegacyAgent.DESCEND_RANGE_UNLIMITED` (Task 1); the file's existing private helpers `registryWithAgent(TargetGoal)`, `spawn(helper, registry, BlockPos, String)`, `settle(BotRegistry, int)` and `navigation(BotRegistry)`.
- Produces: nothing later tasks consume.

**Why this test is shaped the way it is.** `checkDown` returns false for **three** unrelated reasons: the cap, either free-space ray reaching the target, or `getStandingOn()` coming back empty. A lone negative assertion therefore proves nothing — it passes just as happily when the geometry is wrong. The test asserts the *same* bot on the *same* geometry digs at `unlimited` and does not at 8. The restrictive half runs first so no state needs clearing between them; a refused `checkDown` touches nothing.

---

- [ ] **Step 1: Write the failing test**

In `AgentTests.java`, immediately after the closing brace of `a_bot_mines_down_toward_a_target_far_below`, insert:

```java
    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x30x15", floor = true)
    @TestHolder("a_stuck_bot_will_not_tunnel_down_toward_a_distant_target")
    static void a_stuck_bot_will_not_tunnel_down_toward_a_distant_target(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);

        // Something to stand on. An empty getStandingOn() is one of the three ways checkDown
        // returns false, and the one most likely to make this test pass for the wrong reason.
        for (int x = 1; x <= 3; x++) {
            for (int z = 6; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 19, z), Blocks.STONE);
            }
        }

        // A wall across the line of sight, so neither ray reaches the target -- the second of
        // the three. Both rays run at z = 7.5 exactly, since the bot and the target share it and
        // checkFreeSpace steps linearly, so one column would do; three is margin. The rays cross
        // x = 7 at about y = 12 and y = 13, well inside the span.
        for (int y = 1; y <= 22; y++) {
            for (int z = 6; z <= 8; z++) {
                helper.setBlock(new BlockPos(7, y, z), Blocks.STONE);
            }
        }

        Bot bot = spawn(helper, registry, new BlockPos(2, 20, 7), "Digger");

        // settle, not run: a freshly spawned bot has no standingOn until it has ticked, and the
        // agent must not run at all here -- this test calls the decision directly.
        settle(registry, 10);

        Navigation navigation = navigation(registry);
        Vec3 target = helper.absoluteVec(new Vec3(13.5, 1, 7.5));

        // Eleven blocks out and nineteen up. Outside a range of 8, and outside the second
        // branch's own ten as well, so a refusal here can only be the cap.
        boolean capped = navigation.checkDown(bot, target, false, true, 8);

        helper.assertFalse(capped, "a stuck bot 11 blocks out must not tunnel down at range 8");
        helper.assertTrue(registry.state().crackList.isEmpty(),
                "and it must not have started breaking anything");

        // The other half of the pair, on the same bot and the same geometry. If THIS fails, the
        // setup is wrong -- a ray is reaching the target, or the bot is standing on nothing --
        // and the assertion above proved nothing at all.
        boolean uncapped = navigation.checkDown(bot, target, false, true,
                LegacyAgent.DESCEND_RANGE_UNLIMITED);

        helper.assertTrue(uncapped, "the same bot must tunnel down once the cap is lifted");
        helper.assertTrue(!registry.state().crackList.isEmpty(),
                "and must have started breaking the block underfoot");

        registry.reset();
        helper.succeed();
    }
```

- [ ] **Step 2: Verify the test actually fails without the cap**

Temporarily make `mayDigDown` ignore the range, so the test is proven to be testing something:

```java
        return withinTargetXZ || sameXZ;
```

Then:

```bash
./gradlew runGameTestServer
```

Expected: `a_stuck_bot_will_not_tunnel_down_toward_a_distant_target` FAILS on "a stuck bot 11
blocks out must not tunnel down at range 8".

If it fails on the **second** assertion instead, the geometry is wrong — a ray is reaching the
target, or the bot is not grounded — and the first assertion was never proving anything. Fix the
setup before going on; that is the whole reason this test is a pair.

Three of `NavigationTest`'s cases assert on this same predicate, so they fail too while the line
is broken. That is expected and is undone by the next step. Do not "fix" them.

- [ ] **Step 3: Restore `mayDigDown`**

```java
        return withinTargetXZ || (sameXZ && horizontal < range);
```

- [ ] **Step 4: Run the GameTests to verify they pass, capturing the output**

Capture it rather than reading `run/logs/latest.log` afterwards: that file is whatever server ran
most recently and is rotated on the next start, so it is the wrong file the moment anything else
runs.

```bash
./gradlew runGameTestServer 2>&1 | tee gametest-run.log
```

Expected: 148 tests, all pass.

- [ ] **Step 5: Confirm the test is actually registered**

```bash
grep -c "a_stuck_bot_will_not_tunnel_down_toward_a_distant_target" gametest-run.log
```

Expected: at least 1. A GameTest missing `@TestHolder` is silently unregistered **and the suite
still reports success** — five tests once sat dead that way. A green run is not evidence the test
ran; its name in the output is. Delete `gametest-run.log` afterwards; it is not part of the
change.

- [ ] **Step 6: Commit**

```bash
git add src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java
git commit -m "test: pin the descent cap in-world

A pair of assertions on one setup, not a lone negative: checkDown returns
false for three unrelated reasons -- the cap, either free-space ray
reaching the target, or getStandingOn() coming back empty -- so 'the bot
did not dig' proves nothing on its own. The same bot on the same geometry
must dig at unlimited and refuse at 8. If the unlimited half fails, the
geometry is wrong rather than the rule.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Documentation

**Files:**
- Modify: `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md:9987` — append deviation 33
- Modify: `README.md:228-232` — the Equipment and behaviour table
- Modify: `docs/backlog.md:83-84` — the pathfinding-visualiser claim

**Interfaces:**
- Consumes: everything from Tasks 1–3. Produces nothing.

---

- [ ] **Step 1: Register deviation 33**

In `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md`, find entry 32 (it ends with the line `` `docs/superpowers/specs/2026-09-13-shears-and-tool-coverage-design.md`. ``) and insert immediately after it, before the blank line and `And two things found in Plan D that are **not** deviations:`:

```markdown
33. **`checkDown`'s first branch is capped on horizontal distance.** Upstream gated it on
    `withinTargetXZ || sameXZ` and bounded it horizontally only through the first of those, so a
    stuck bot dug down at any distance at all — `sameXZ` is `center`'s stuckness sample and says
    nothing about how far away the target is. It now requires
    `withinTargetXZ || (sameXZ && horizontal < descendRange)`, default 8. The cap deliberately
    does not apply to `withinTargetXZ`, which admits bots up to 8.66 blocks out because it
    measures against the aim point. `/tplus descendrange unlimited` restores upstream's
    behaviour; the second branch's own `horizontal < 10` is untouched. Designed in
    `docs/superpowers/specs/2026-09-14-descend-range-design.md`.
```

- [ ] **Step 2: Update the README command reference**

In `README.md`, replace:

```markdown
| `/tplus offsets <true\|false>` | converge on a ring around the target instead of one point | on |

Those last three require their argument — there is no report form.
```

with:

```markdown
| `/tplus offsets <true\|false>` | converge on a ring around the target instead of one point | on |
| `/tplus descendrange <blocks>` | how close, horizontally, a stuck bot must be before it tunnels down toward a target below it | 8 |

`agent`, `drops` and `offsets` require their argument — there is no report form.
`descendrange` reports when given none, and takes `unlimited` to lift the cap entirely, which is
what bots did before it existed: one that lost its footing fifty blocks from a target below would
tunnel straight down to its level and then mine across.
```

- [ ] **Step 3: Update the backlog**

In `docs/backlog.md`, replace:

```markdown
it. That is why bots prefer straight lines, and why one below you will mine straight down to your
level and then across rather than cutting the diagonal.
```

with:

```markdown
it. That is why bots prefer straight lines, and why one below you will mine straight down to your
level and then across rather than cutting the diagonal.

The descent half of that is now bounded — `/tplus descendrange` stops a stuck bot digging down
until it is within 8 blocks horizontally — but bounding a straight line is not the same as having
a path. There is still no graph, no cost function and no diagonal.
```

- [ ] **Step 4: Correct the test counts in `CLAUDE.md` and `README.md`**

Five lines, in three files. Two of them were already wrong before this change — the GameTest suite
was **147** on clean `master`, not the 146 both files claim — so this corrects a stale number as
well as accounting for the tests this plan adds. Verify the numbers against an actual run rather
than trusting these, then apply:

`CLAUDE.md:52-53`:

```
./gradlew build              # compile + 109 unit tests
./gradlew runGameTestServer  # 148 GameTests, headless, ~10s
```

`README.md:82`:

```markdown
`build` compiles and runs the 109 unit tests; `jar` writes `build/libs/tplus-5.0.0-ALPHA.jar`.
```

`README.md:363-364`:

```markdown
| **109 unit tests** (`src/test`) | Pure maths — vectors, offsets, the scheduler | Anything needing a world |
| **148 GameTests** (`src/gametest`) | Integration: mining, clutching, block rules | Anything needing a real client or server runtime |
```

- [ ] **Step 5: Verify the docs are consistent with what shipped**

```bash
grep -n "descendrange" README.md docs/backlog.md docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md docs/superpowers/specs/2026-09-14-descend-range-design.md
```

Expected: hits in all four. Check by eye that the default quoted in each is **8** and matches `LegacyAgent.descendRange`, and that no file still claims the README's settings table has no report form.

- [ ] **Step 6: Full verification before committing**

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: BUILD SUCCESSFUL, 109 unit tests, 148 GameTests, all passing.

- [ ] **Step 7: Commit**

```bash
git add CLAUDE.md README.md docs/backlog.md docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md
git commit -m "docs: register the descent cap and document the command

Deviation 33, the README command reference, and the backlog's
pathfinding-visualiser section -- which said a bot below you mines
straight down to your level and then across. Half of that is no longer
true, and the half that is has nothing to do with pathfinding: bounding a
straight line is not a path.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## After the plan: pick the number

The spec leaves one question open, and it is not answerable from a test suite. `descendRange`
ships at **8**; 10 is the other candidate, and reproduces what a flat cap at the top of
`checkDown` would do. Settle it in a real client session, not by argument:

1. `./gradlew runServer`, then `./gradlew runClient` and connect to localhost.
2. Stand somewhere 40–60 blocks away from the spawn point and 20–30 blocks down, with terrain
   between you and the bot — a cave, a dug-out base, anything that makes it lose its footing.
3. `/tplus create Hunter 1`, then watch what it does at each of the three settings:
   `/tplus descendrange unlimited` (the old behaviour), `/tplus descendrange 10`, and
   `/tplus descendrange 8`.
4. Repeat with the bot underground rather than on the surface, which changes what `checkSide`
   finds ahead of it.

What to watch for: a bot that reaches you *faster* is the point, but a bot that paces against a
wall it cannot break, or that tunnels a long horizontal corridor at its own Y when dropping a few
blocks would have been obviously better, is the failure mode that would argue for a larger number.

A real client is also the only tier that catches rendering, packet-ordering and projectile bugs —
a manual session once found four defects invisible to 132 GameTests *and* to RCON. Do not treat
the green suite as the end of this.

If the number changes, it is a one-line edit to `LegacyAgent.descendRange` plus the default quoted
in the README and in deviation 33.
