# Walk vs Jump Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `Navigation.move` a second gear — walk instead of jump when a solid block at `feet+2` makes the jump useless — so a tunnelling bot stops spending ~37% of its time airborne and idle.

**Architecture:** One branch at the end of `move`, gated on a single block read. The action (`bot.walk(vel.setY(0))`) is upstream's; the trigger is new. Scoped to low ceilings so bots keep their hopping gait in the open and in combat, and so `walk`'s 0.4 clamp only ever lands where the bot is mining-bound.

**Tech Stack:** Java 21, NeoForge for Minecraft 26.2, NeoForge test framework GameTests (`src/gametest`), Gradle, `tools/rcon.py` for the dev-server measurement.

## Global Constraints

- **Fidelity is the point.** Method bodies are translated from the Paper plugin, not redesigned. Every deliberate divergence goes in the deviation register in `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` — do not start a new list. This change is **deviation 34**.
- The faithfulness oracle is the `paper-original` branch. Read the original of any file with `git show paper-original:<path>`. **Never modify that branch.**
- Comments explain *why*, especially where the code looks wrong. When a line diverges from upstream, say what upstream did and what changed, at the line that changed.
- `@TestHolder` is **required** on every GameTest. Without it the test is silently unregistered and the suite still reports success.
- Prefer a direct call to ticking and waiting. `move()` adds `Math.random()` to every jump, so a test that runs 200 ticks and asserts on position measures the walk, not the decision.
- A fresh `ServerPlayer` carries `invulnerableTime = 60` and `noFallTicks = 60`, and is not grounded until it has ticked. Use `settle()`.
- **Invoke the RCON client as `python tools/rcon.py`, never `python3`** — `python3` on this machine is the Microsoft Store placeholder and exits without running anything.
- The spec is `docs/superpowers/specs/2026-09-14-walk-vs-jump-design.md`. Read it before Task 1.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java` | Modify | The gear. New private `canJumpHere`; the branch at the end of `move`; the javadoc that currently says bots never walk. |
| `src/main/java/net/nuggetmc/tplus/agent/legacy/SurroundingScan.java` | Modify | Comment only — `isWalkableStep` gains a note that its `isAir` spelling is now a correctness dependency. |
| `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java` | Modify | One paired in-world test. |
| `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` | Modify | Deviation 34. |
| `docs/backlog.md` | Modify | The cost of a step, in the pathfinding section. |

`README.md` needs nothing — no new command, no operator-visible setting.

---

### Task 1: The walk gear

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java:47-108`
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/SurroundingScan.java:296-302`
- Modify: `src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java`

**Interfaces:**
- Consumes: the file's existing private GameTest helpers `registryWithAgent(TargetGoal)`, `spawn(helper, registry, BlockPos, String)`, `settle(BotRegistry, int)`, `navigation(BotRegistry)`; and `Bot.walk(MotionVec)`, `Bot.jump(MotionVec)`, `Bot.getVelocity()`, `Bot.setVelocity(MotionVec)`, all already public.
- Produces: `private static boolean Navigation.canJumpHere(Bot bot, Vec3 pos)`. `move`'s signature is unchanged.

No new imports in `Navigation` — `BlockPos`, `ServerLevel` and `Vec3` are already imported and `BlockRules` is in the same package.

---

- [ ] **Step 1: Write the failing GameTest**

In `AgentTests.java`, immediately after the closing brace of `a_stuck_bot_will_not_tunnel_down_toward_a_distant_target`, insert:

```java
    @GameTest(timeoutTicks = 400)
    @EmptyTemplate(value = "15x6x15", floor = true)
    @TestHolder("a_bot_under_a_low_ceiling_walks_instead_of_jumping")
    static void a_bot_under_a_low_ceiling_walks_instead_of_jumping(ExtendedGameTestHelper helper) {
        BotRegistry registry = registryWithAgent(TargetGoal.NEAREST_BOT);

        Bot walker = spawn(helper, registry, new BlockPos(4, 1, 4), "Walker");
        Bot quarry = spawn(helper, registry, new BlockPos(10, 1, 4), "Quarry");

        // jump() gates on getGroundTicks() > 1, and a freshly spawned bot is not grounded at
        // all -- without this the jump half fails for a reason that has nothing to do with the
        // rule under test.
        settle(registry, 10);

        Navigation navigation = navigation(registry);
        BlockPos ceiling = new BlockPos(4, 3, 4);

        // The walk half runs FIRST, and the order is not cosmetic: jump() sets jumpTicks = 4,
        // which blocks the next jump for four ticks, so testing the jump first would make the
        // walk half pass for the wrong reason.
        helper.setBlock(ceiling, Blocks.STONE);
        walker.setVelocity(new MotionVec(0, 0, 0));

        navigation.move(walker, quarry, walker.position(), quarry.position());

        MotionVec walked = walker.getVelocity();

        helper.assertTrue(walked.getY() < 0.1,
                "under a ceiling the bot must not launch itself; y velocity was " + walked.getY());

        // And it must actually have moved. Without this the test passes just as happily when
        // move() does nothing at all, which is the failure a lone negative cannot see.
        helper.assertTrue(Math.hypot(walked.getX(), walked.getZ()) > 0.1,
                "it must still travel horizontally; velocity was " + walked);

        // Same bot, same target, same tick -- only the ceiling changes.
        helper.setBlock(ceiling, Blocks.AIR);
        walker.setVelocity(new MotionVec(0, 0, 0));

        navigation.move(walker, quarry, walker.position(), quarry.position());

        MotionVec jumped = walker.getVelocity();

        helper.assertTrue(jumped.getY() > 0.3,
                "with headroom the bot must jump as it always did; y velocity was " + jumped.getY());

        registry.reset();
        helper.succeed();
    }
```

- [ ] **Step 2: Run it to verify it fails, and fails on the right half**

```bash
./gradlew runGameTestServer --rerun-tasks 2>&1 | tee walkjump-run.log
```

Expected: `a_bot_under_a_low_ceiling_walks_instead_of_jumping` FAILS on **"under a ceiling the bot must not launch itself"**. `move` still jumps unconditionally, so the y velocity will be about 0.35-0.4.

If it instead fails on the *horizontal* assertion, `move` returned early — most likely the bot is not grounded, so increase `settle`. If it fails on the *jump* half, the setup is wrong rather than the rule.

- [ ] **Step 3: Add `canJumpHere` to `Navigation`**

In `Navigation.java`, insert this immediately **above** the `move` javadoc (currently line 47):

```java
    /**
     * Whether the bot has headroom worth jumping into.
     *
     * <p>A bot is 1.8 blocks tall, so a solid block at {@code feet+2} leaves 0.2 blocks of
     * clearance: the 0.4 impulse is spent on the ceiling and the bot spends the whole arc
     * airborne, during which {@code tickBot}'s grounded branch does not run and it neither
     * scans nor mines nor decides. Measured at about 37% of a tunnelling bot's time.
     *
     * <p><b>The {@code isAir} spelling is load-bearing and must match
     * {@link SurroundingScan#isWalkableStep}'s first term.</b> That method is what decides a
     * knee-high block is a step to hop rather than a wall to mine, and it requires air at the
     * same position. So under a ceiling it returns false and the step is mined instead — which
     * is what makes "the bot walks into a step it cannot climb and jams" impossible rather than
     * merely unlikely. Changing either site to {@code blocksPath} or {@code isNonSolid} looks
     * like tidying and breaks that guarantee.
     */
    private static boolean canJumpHere(Bot bot, Vec3 pos) {
        ServerLevel level = (ServerLevel) bot.level();
        return BlockRules.isAir(level.getBlockState(BlockPos.containing(pos).above(2)));
    }

```

- [ ] **Step 4: Replace the unconditional jump**

Still in `Navigation.java`, at the end of `move`, replace:

```java
        vel.setY(vel.getY() - Math.random() * 0.05);

        bot.jump(vel);
    }
```

with:

```java
        vel.setY(vel.getY() - Math.random() * 0.05);

        // The one divergence in this method. Upstream jumped unconditionally here; its only
        // walk path lived in the neural-network branch, was gated on `distance <= 6`, sat inside
        // the left/right strafe logic and jumped anyway ten ticks later -- a close-quarters juke
        // that never fired during a tunnel. The call below is upstream's, flattened vector and
        // all; the trigger and the dropped delayed jump are this port's. See canJumpHere.
        //
        // vel already carries the bot's velocity, added near the top of this method, and walk()
        // adds it a second time. That double-count is upstream's too, and it is harmless rather
        // than overlooked: walk() clamps the sum to 0.4, so the bot saturates there within a
        // tick or two and stays. Removing the double-count would change the speed, not fix a
        // bug -- and the 0.4 is only safe because this branch is reached under a ceiling, where
        // the bot is mining-bound at ~30 ticks per block and the clamp never binds.
        if (canJumpHere(bot, pos)) {
            bot.jump(vel);
        } else {
            bot.walk(vel.setY(0));
        }
    }
```

- [ ] **Step 5: Correct `move`'s javadoc, which now says the opposite of what the code does**

Still in `Navigation.java`, replace:

```java
     * <p>Ported from {@code move}. Bots do not walk: every step is a jump with a horizontal
     * impulse, which is why they look the way they do and why {@code isBotOnGround} gates the
     * whole method.
     *
     * <p>The neural-network branch (upstream lines 243-297) is omitted — see plan correction 4.
     * It rotated the impulse by a learned left/right bias and chose between a jump and a
     * walk-then-jump; all of it is unreachable with the AI deferred.
     */
```

with:

```java
     * <p>Ported from {@code move}. Bots hop rather than walk: in the open, every step is a jump
     * with a horizontal impulse, which is why they look the way they do and why
     * {@code isBotOnGround} gates the whole method. Under a low ceiling they walk instead —
     * see {@link #canJumpHere}, which is this port's and not upstream's.
     *
     * <p>The neural-network branch (upstream lines 243-297) is omitted — see plan correction 4.
     * It rotated the impulse by a learned left/right bias and chose between a jump and a
     * walk-then-jump; all of it is unreachable with the AI deferred. Note that the walk gear
     * below is <b>not</b> a partial restoration of it: that branch only ran within 6 blocks of
     * the target and jumped anyway ten ticks later.
     */
```

- [ ] **Step 6: Note the dependency at the other end**

In `SurroundingScan.java`, replace `isWalkableStep`'s javadoc:

```java
    /**
     * Whether a knee- or ankle-height block is a step the bot can simply walk up.
     *
     * <p>Upstream's late rejection for the {@code _D} and {@code _D_2} offsets: with clear air
     * two above both the bot and the block, and the block not a fence or gate, it is a step.
     * Without this a bot mines every staircase it meets.
     */
```

with:

```java
    /**
     * Whether a knee- or ankle-height block is a step the bot can simply walk up.
     *
     * <p>Upstream's late rejection for the {@code _D} and {@code _D_2} offsets: with clear air
     * two above both the bot and the block, and the block not a fence or gate, it is a step.
     * Without this a bot mines every staircase it meets.
     *
     * <p><b>The first term is shared with {@code Navigation.canJumpHere}</b>, which decides
     * whether a bot jumps or walks. Both ask {@code isAir} at {@code botPos.above(2)}, and the
     * pairing is deliberate: under a ceiling this returns false, so a step is mined rather than
     * hopped, and a walking bot is therefore never asked to climb something it cannot. Respell
     * one of the two and bots jam against knee-high blocks inside their own tunnels.
     */
```

- [ ] **Step 7: Run the GameTest to verify it passes**

```bash
./gradlew runGameTestServer --rerun-tasks 2>&1 | tee walkjump-run.log
```

Expected: `Found 148 tests` and `All 149 required tests passed`. The two counters differ by one — `Found N` is the mod's registered count and is the figure the docs quote.

- [ ] **Step 8: Confirm the test actually ran**

```bash
grep -c "a_bot_under_a_low_ceiling_walks_instead_of_jumping" walkjump-run.log
```

Expected: at least 1. A GameTest missing `@TestHolder` is silently unregistered **and the suite still reports success**, so a green run is not evidence the test ran. Delete `walkjump-run.log` afterwards; it is not part of the change.

- [ ] **Step 9: Run the unit suite too**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, 109 unit tests, 0 failures. Nothing here touches them, but `Navigation` is on the main source path and a compile error would surface here first.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java src/main/java/net/nuggetmc/tplus/agent/legacy/SurroundingScan.java src/gametest/java/net/nuggetmc/tplus/gametest/AgentTests.java
git commit -m "feat: bots walk instead of jumping under a low ceiling

move() had one gear. In a two-block corridor a jump is 0.2 blocks of
headroom followed by a fall, and it is worse than slow movement: tickBot's
whole decision branch sits behind isBotOnGround(), so an airborne bot does
not scan, mine or decide. Measured at ~17 of the ~47 ticks a tunnelling
bot spends per block of forward progress.

The trigger is one block read -- a solid block at feet+2 -- and is
deliberately spelled isAir to match SurroundingScan.isWalkableStep's first
term. Under a ceiling that method returns false and a knee-high block is
mined rather than hopped, so a walking bot is never asked to climb. Respell
either site and bots jam against steps inside their own tunnels; both now
carry a comment naming the other.

The action is upstream's bot.walk(vel.setY(0)). The trigger is not:
upstream's only walk path was gated on distance <= 6, sat inside the
strafe logic, and jumped anyway ten ticks later, so it never fired during
a tunnel. Deviation 34.

move()'s javadoc said bots never walk, which is now false; corrected.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Measure it, and be willing to throw it away

**Files:** none. This task produces a number, and a decision.

**Interfaces:**
- Consumes: the gear from Task 1.
- Produces: a measured ticks-per-block figure that Task 3's docs quote.

**Why this is a task and not a verification step.** The spec's prediction is ~33-35 ticks per block against a **47.4** baseline. `Bot.walk` has never moved a bot in production — its two GameTests cover the vector arithmetic only — so if the velocity does not survive ground friction, the number comes back near 47 and **the design is wrong rather than the tuning**. A reviewer can legitimately reject here. Do not tune the constant to rescue it; report the number.

---

- [ ] **Step 1: Start a dev server**

```bash
./gradlew runServer
```

Leave it running. If it dies with an opaque `IOException` about the session lock, a previous `runServer` is still alive — kill it first.

- [ ] **Step 2: Build the arena**

The same corridor the 47.4 baseline was measured in: 55 blocks of horizontal separation, target 27 below, solid stone throughout so both free-space rays are blocked and the bot is walled in.

```bash
python tools/rcon.py "forceload add 1000 -5 1060 5" "fill 1000 60 -5 1060 92 5 stone" "fill 1001 89 -1 1003 91 1 air" "fill 1055 62 -2 1059 65 2 air"
```

- [ ] **Step 3: Verify the arena before trusting anything measured in it**

```bash
python tools/rcon.py "execute if block 1002 88 0 stone" "execute if block 1002 89 0 air" "execute if block 1002 91 0 stone" "execute if block 1030 89 0 stone" "execute if block 1057 62 0 air"
```

Expected: five `Test passed`. The third matters most for this change — `1002 91 0` is `feet+2` for a bot standing at y=89, and it must be **stone**, or the walk gear will not engage and the run measures nothing.

- [ ] **Step 4: Spawn the target and the bot, in that order**

```bash
python tools/rcon.py "tplus removeall" "kill @e[type=armor_stand]" "summon armor_stand 1057.5 62 0.5 {Invulnerable:1b}" "tplus offsets false" "execute positioned 1002.5 89.0 0.5 run tplus create Hunter 1 none none netherite"
```

`offsets false` makes the bot aim at the target itself rather than at a random point up to 3 blocks off, so it carves a straight corridor instead of a diagonal one. It does not touch the walk gear.

- [ ] **Step 5: Set the enemy target AFTER the bot exists**

```bash
python tools/rcon.py "tplus list" "tplus enemytarget generic minecraft:armor_stand"
```

Expected: `1 bot(s): Hunter`, then `Now hunting minecraft:armor_stand for 1 bot(s).`

**The `for N bot(s)` count is the check and N must be 1.** `/tplus enemytarget` writes per-bot state to the bots that exist when it runs; a bot created afterwards carries `EnemyTarget.NONE` and stands perfectly still. Running these two commands in the other order costs ~80 seconds of a motionless bot and looks exactly like a bug in the movement code.

- [ ] **Step 6: Sample the bot's position for 100 seconds**

```bash
for i in $(seq 1 10); do P=$(python tools/rcon.py "tplus info Hunter" 2>/dev/null | grep Position | sed 's/.*Position: //' | tr ',' '.' | sed 's/\. /, /g'); echo "t+$((i*10))s  ${P:-<no bot>}"; sleep 10; done
```

Take the first and last samples that are both still at **y = 89** — the horizontal phase — and compute:

```
ticks per block = (elapsed seconds × 20) ÷ (x_last − x_first)
```

- [ ] **Step 7: Compare against the baseline and decide**

| | |
|---|---|
| Baseline (on `origin/master`) | **47.4** ticks per block, replicated at 46 and 48 |
| Spec prediction | 33-35 |
| Mining floor (cannot go below) | ~30 |

- **33-36:** as designed. Continue to Task 3, quoting the measured figure.
- **37-44:** a real but smaller win. Continue, and quote the measured figure rather than the prediction — do not describe it as "about a quarter faster" if it is not.
- **Near 47:** the walk velocity is not surviving ground friction. **Stop.** Report the number, and revert Task 1 rather than tuning `Bot.walk`'s 0.4 clamp to chase it — a speed constant tuned to rescue a failed premise is how a codebase acquires magic numbers.

- [ ] **Step 8: Confirm the shape of the tunnel is unchanged**

```bash
python tools/rcon.py "execute if block 1002 88 0 stone"
```

Expected: `Test passed`. The floor at the start of the corridor must still be intact — the walk gear must not have disturbed the descent cap from the previous change. A failure here means the bot dug down at the start, and that is a regression in `checkDown`, not in this work.

- [ ] **Step 9: Stop the server**

```bash
python tools/rcon.py "stop"
```

`stop` never replies — the server closes the socket, so a close or timeout is success.

- [ ] **Step 10: Record the number in the spec**

Add the measured figure to `docs/superpowers/specs/2026-09-14-walk-vs-jump-design.md`, at the end of the "Expected gain, and the risk that it is zero" section:

Write the figure you measured in Step 6, not the prediction. For example, a run that came out at
34.2 would be recorded as:

```markdown
**Measured:** 34.2 ticks per block against the 47.4 baseline, in the same 55-block corridor with
`offsets false` — inside the predicted 33-35, and 4 ticks above the ~30 mining floor, which is
about what a block of walking at the 0.4 clamp should cost.
```

If the number missed the prediction, say so in that second sentence and say what it suggests,
rather than quietly widening the predicted range.

```bash
git add docs/superpowers/specs/2026-09-14-walk-vs-jump-design.md
git commit -m "docs: record the measured walk-gear speedup

<N> ticks per block against the 47.4 baseline, same corridor, offsets
off. The spec predicted 33-35 against a ~30 mining floor.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Documentation

**Files:**
- Modify: `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` — append deviation 34 after entry 33
- Modify: `docs/backlog.md` — the pathfinding-visualiser section

**Interfaces:**
- Consumes: the measured figure from Task 2. Produces nothing.

---

- [ ] **Step 1: Register deviation 34**

In `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md`, find entry 33 (it ends with the line `` `docs/superpowers/specs/2026-09-14-descend-range-design.md`. ``) and insert immediately after it, before the blank line and `And two things found in Plan D that are **not** deviations:`:

```markdown
34. **`move` walks instead of jumping under a low ceiling.** Upstream jumped unconditionally
    outside its neural-network branch, and that branch's walk path was gated on `distance <= 6`,
    sat inside the left/right strafe logic, and jumped anyway ten ticks later — a close-quarters
    juke that never fired during a tunnel. The action here is upstream's `bot.walk(vel.setY(0))`;
    the trigger, `!BlockRules.isAir(feet+2)`, and the dropped delayed jump are this port's. The
    `isAir` spelling is shared with `SurroundingScan.isWalkableStep` on purpose: under a ceiling
    that method returns false, so a knee-high block is mined rather than hopped and a walking bot
    is never asked to climb. Scoped to low ceilings, so bots keep their hopping gait in the open
    and in combat and `walk`'s 0.4 clamp never lands anywhere it binds. Designed in
    `docs/superpowers/specs/2026-09-14-walk-vs-jump-design.md`.
```

- [ ] **Step 2: Put the cost of a step in the backlog**

In `docs/backlog.md`, find the pathfinding-visualiser paragraph that ends:

```markdown
The descent half of that is now bounded — `/tplus descendrange` stops a stuck bot digging down
until it is within 8 blocks horizontally — but bounding a straight line is not the same as having
a path. There is still no graph, no cost function and no diagonal.
```

and insert immediately after it:

```markdown
What a step costs is now measured, which it never was before. A bot tunnelling with netherite
tools spent about 47 ticks per block of forward progress, of which only ~30 was mining — the rest
was the jump arc, and `tickBot`'s decision branch sits behind `isBotOnGround()`, so that time was
idle rather than merely slow. Bots now walk under a low ceiling instead. That is a constant-factor
win on a straight line and still not a path.
```

- [ ] **Step 3: Check the docs against what shipped**

```bash
grep -n "canJumpHere\|isWalkableStep" src/main/java/net/nuggetmc/tplus/agent/legacy/Navigation.java src/main/java/net/nuggetmc/tplus/agent/legacy/SurroundingScan.java
```

Expected: both files reference both names — `Navigation` naming `isWalkableStep` in `canJumpHere`'s javadoc, and `SurroundingScan` naming `canJumpHere` in `isWalkableStep`'s. If either is missing, the correctness dependency is documented in only one direction and the next reader to touch the other site has no warning.

Then confirm the backlog's tick figures match what Task 2 actually measured, not the prediction.

- [ ] **Step 4: Full verification**

```bash
./gradlew build && ./gradlew runGameTestServer --rerun-tasks
```

Expected: BUILD SUCCESSFUL, 109 unit tests, `Found 148 tests`, all passing.

- [ ] **Step 5: Commit**

```bash
git add docs/backlog.md docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md
git commit -m "docs: register the walk gear

Deviation 34, and the cost of a step in the backlog's pathfinding
section -- which described how bots move without ever saying what a step
costs. It is ~47 ticks per block of forward progress against ~30 of
mining, and the difference was idle time rather than slow movement.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Notes for whoever runs this

**The riskiest step is Task 2, not Task 1.** The code change is six lines and its GameTest proves the branch is taken. What it cannot prove is that walking is *faster*, because that depends on whether `Bot.walk`'s velocity survives ground friction — and nothing in this repo has ever exercised that. Task 2 exists to find out, and its Step 7 includes the instruction to abandon the change. Take it seriously; the alternative is a codebase that carries a gear nobody can show is worth having.

**Two ordering traps, both of which make a green result meaningless:**

- In the GameTest, the walk half must run before the jump half. `jump()` sets `jumpTicks = 4`.
- In the arena, the bot must exist before `/tplus enemytarget` runs, or it hunts nothing and stands still.

**What this deliberately does not do**, each of which would be its own design: let a `noJump` bot walk while it mines downward, and widen the gait choice to open ground, which would put the 0.4 clamp somewhere it actually binds.
