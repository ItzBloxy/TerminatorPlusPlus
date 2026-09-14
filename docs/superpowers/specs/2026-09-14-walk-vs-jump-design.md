# Walk vs jump — design

A tunnelling bot spends about 37% of its time airborne, achieving nothing. `Navigation.move` has
one gear — every step is a jump — and in a two-block corridor a jump is 0.2 blocks of headroom
followed by a fall. This gives `move` a second gear, engaged by the one condition that makes the
first one useless.

Upstream chose between walking and jumping, but its chooser was the neural network and it only ran
within 6 blocks of the target. This trigger is new. Register it as deviation 34.

---

## The measurement

Three runs in a 55-block stone corridor, netherite tools, target 27 blocks below:

| | |
|---|---|
| Forward progress | **47.4 ticks per block** (also measured 46 and 48) |
| Mining | 2 blocks per step × ~15 ticks = **~30 ticks** |
| Unaccounted | **~17.4 ticks, 37%** |

The mining figure is not `BREAK_COST ÷ destroy speed`. `Mining.BREAK_PERIOD` is **2**, so progress
accrues once every two ticks, not once per tick:

```
per run   = max(1, round(speed × BREAK_PERIOD))      netherite on stone: round(9.0 × 2) = 18
runs      = ceil(BREAK_COST ÷ per run)               ceil(120 ÷ 18)     = 7
ticks     = runs × BREAK_PERIOD                      7 × 2              = 14
```

Plus the one-period offset from deviation 15, so ~15 ticks per block. The model is worth trusting
because it reproduces this project's own anchor: iron gives `round(6.0 × 2) = 12` per run,
`ceil(120 ÷ 12) = 10` runs, **20 ticks** — exactly what deviation 15 describes and what
`iron_still_breaks_a_block_in_twenty_ticks` pins by name.

The 37% is the jump arc, and it is worse than slow movement. `tickBot`'s entire decision branch is
gated on `bot.isBotOnGround()`:

```java
if (waterGround || bot.isBotOnGround() || behaviors.onBoat(bot)) {
    ... every terrain check, every mining decision, every call to move() ...
}
```

While airborne the bot does not scan, does not mine and does not decide. It is idle, not merely
slow.

## What upstream did, and why it does not apply

`LegacyAgent.move`'s omitted neural-network branch is the only place upstream ever walked:

```java
if (network.check(BotNode.JUMP)) {
    bot.jump(vel);
} else {
    bot.walk(vel.clone().setY(0));
    scheduler.runTaskLater(plugin, () -> bot.jump(vel), 10);
}
```

Three things disqualify it as a reference for this problem. It is gated on `distance <= 6`, so it
never fires during a long tunnel. It sits inside the left/right strafe logic, making it a
close-quarters juke rather than a travel choice. And its walk branch jumps anyway ten ticks later,
which is the arc we are trying to remove.

So this is new behaviour, not a restoration. The **action** is upstream's — `bot.walk(vel.setY(0))`
is its exact call, flattened vector and all. The trigger and the dropped `runTaskLater` are ours.

`Bot.walk` itself is already ported, with two GameTests. Nothing in `src/main` has ever called it,
because removing the chooser left the jump wired in permanently.

## The rule

One branch at the end of `move`, replacing the unconditional `bot.jump(vel)`:

```java
if (canJumpHere(bot, pos)) {
    bot.jump(vel);
} else {
    bot.walk(vel.setY(0));
}
```

```java
private static boolean canJumpHere(Bot bot, Vec3 pos) {
    ServerLevel level = (ServerLevel) bot.level();
    return BlockRules.isAir(level.getBlockState(BlockPos.containing(pos).above(2)));
}
```

One block read. A bot is 1.8 blocks tall (vanilla; `Bot` does not override its dimensions), so a
solid block at `feet+2` leaves 0.2 blocks of headroom and the 0.4 impulse is spent on the ceiling.
That is the exact physical condition under which a jump buys nothing, which is why it is the
trigger rather than a "tunnelling" state flag.

The trigger is confirmed to fire where intended, from the world the measured runs actually left
behind. Reading the corridor out of the arena — `.` air, `#` stone:

```
y 91: #...######################   <- feet+2, solid the whole length
y 90: #....................#####   <- head
y 89: #...................######   <- feet
```

The bot tunnels at `y89`/`y90` and `y91` is stone from `x1004` onward, so `canJumpHere` returns
false for every step of the run that the 47.4 figure was measured over. The only air at `feet+2` is
the three-block spawn pocket, where the bot has not started tunnelling yet.

### Why `isAir`, and why that spelling is load-bearing

`SurroundingScan.isWalkableStep` decides whether a knee-high block is a step to hop or a wall to
mine, and its first term is the same read:

```java
return BlockRules.isAir(level.getBlockState(botPos.above(2)))
        && BlockRules.isAir(level.getBlockState(scan.pos().above(2)))
        && !BlockRules.isFence(blockState)
        && !BlockRules.isGate(blockState);
```

Under a solid ceiling that returns false, so the step is **mined instead of hopped**. The walk gear
therefore engages precisely where the bot never needs to climb, and the obvious failure — walking
into a step it cannot clear and jamming — is structurally impossible rather than merely unlikely.

That guarantee holds only while both sites spell the test the same way. `blocksPath` or
`isNonSolid` would look like tidying and would break it, so the identity gets a comment at both
sites naming the other.

This also covers the case the trigger appears to over-reach on: a bot under a tree, a doorway or
any overhang switches to walking. It is still correct there, for the same reason — anything in its
way gets mined rather than hopped.

### What does not change

- `state.slow` still halves the vector. It already sets Y to 0, so flattening again is a no-op.
- `state.noJump` still suppresses movement. A bot mining downward stays put; widening that to "may
  still walk" is a separate decision and is out of scope. Precisely: only `tickBot`'s `case 1`
  tests `noJump` — `case 2` calls `move` without checking it — but `case 2` is unreachable, because
  `checkSide` can never return 2. That is upstream's dead arm, faithfully dead, and noted on the
  register already. The suppression is therefore total in practice but not by construction, and
  anything that revives `case 2` has to revisit this.
- The descent path is untouched. `SurroundingScan` adds `noJump` for 15 ticks when it scans BELOW,
  and `tickBot` skips `move` for those bots.

### The velocity is double-counted, deliberately

`move` already does `vel.add(bot.getVelocity())`, and `Bot.walk` adds current velocity again:

```java
MotionVec sum = getVelocity().add(vel);
if (sum.length() > max) sum.normalize().multiply(max);
```

Upstream's branch had the same double-count. It does not matter, because `walk` clamps to 0.4: the
bot saturates at 0.4 blocks/tick within a tick or two and stays there. Recorded here so that a
later reader does not "fix" it and silently change the speed.

The clamp is roughly twice sprint speed, which would be alarming in the open. A tunnelling bot is
mining-bound at ~30 ticks per block, so the cap never binds. The narrow trigger is what makes the
speed safe, and widening the trigger would need this reconsidered.

## Expected gain, and the risk that it is zero

Mining stays at ~30 ticks. The ~17.4-tick arc becomes a few ticks of walking at 0.4 blocks/tick
plus a tick or two of acceleration. That predicts **roughly 33-35 ticks per block, about a quarter
faster**.

It is a prediction, not a result, and it is the part most likely to be wrong: **`Bot.walk` has
never moved a bot in production.** Its two GameTests check the vector arithmetic and nothing else,
so whether that velocity survives ground friction into useful movement is untested. If the arena
comes back near 47, the design is wrong rather than the tuning, and the honest response is to
abandon it rather than tune the constant.

## Testing

**GameTest**, one test, paired on a single setup — the same shape the descend-range test uses, and
for the same reason: a single assertion cannot distinguish "walked" from "did not move".

| Half | Setup | Assertion |
|---|---|---|
| Walk | stone at `feet+2` | `getVelocity().getY() < 0.1` |
| Jump | ceiling removed | `getVelocity().getY() > 0.3` |

`getJumpTicks()` would be the natural discriminator and is **not usable**: it is package-private in
`net.nuggetmc.tplus.bot` and GameTests live in `net.nuggetmc.tplus.gametest`. `getVelocity()` is
public and separates the cases cleanly, since `jump` writes Y directly to 0.35-0.4 while `walk`
leaves Y at whatever gravity left it.

Two ordering constraints, both of which make the test pass for the wrong reason if ignored:

1. **The walk half runs first.** `jump` sets `jumpTicks = 4`, which blocks the next jump for four
   ticks, so testing jump first poisons the walk half. Zero the velocity between halves with
   `setVelocity`.
2. **`settle` before either call.** `jump` gates on `getGroundTicks() > 1`, so a freshly spawned bot
   cannot jump at all and the jump half would fail for reasons unrelated to the rule.

The target is a second bot, not a mock player — `makeMockServerPlayer` has a null connection and
crashes the server tick.

**No unit test.** The decision is a `BlockState` read with no world-free core to extract. Every
`BlockRules` test in this repo is a GameTest, and the one unit test that touches `Blocks` uses only
the constants, never `defaultBlockState()`. A `static boolean shouldWalk(boolean lowCeiling)` would
be tautological.

**Measurement is the real verification.** The baseline is banked: 47.4 ticks per block, three runs,
against the commit now on `origin/master`. Re-run the same arena — 55-block stone corridor, target
27 below, netherite tools, `/tplus offsets false` for a straight line — and compare.

**Regression.** Every existing movement test runs under open sky, where `feet+2` is air and the jump
path is unchanged.

`a_bot_mines_down_toward_a_target_far_below` is the closest call, and the reason it is safe is worth
stating properly rather than by position. Its bot starts above a 3×3 stone column and ends up
*inside* it, where `feet+2` is solid and the new branch would fire — but it never reaches the
branch. A descending bot is handled by `checkDown`, which returns true and makes `tickBot` return
before `move` is called at all; and in the gaps between descent steps it is falling, so `move`
early-returns on `isBotOnGround()`. The walk gear cannot influence a descent.

The full 147 still has to run.

## Docs

1. A comment at the branch in `move`, and a matching one at `SurroundingScan.isWalkableStep`, each
   naming the other — the shared `isAir` spelling is a correctness dependency and neither site is
   self-explanatory alone.
2. Deviation 34 on Plan B's register. That is the list every later plan extends; do not start a new
   one.
3. `docs/backlog.md`. Its pathfinding-visualiser section describes how bots move without saying what
   a step costs; the 37% figure belongs there.

`README.md` needs nothing. There is no new command and no operator-visible setting.

## Deviations to register

34. **`move` walks instead of jumping under a low ceiling.** Upstream jumped unconditionally
    outside its neural-network branch; that branch's walk path was gated on `distance <= 6`, sat
    inside the strafe logic, and jumped anyway after ten ticks. The action here is upstream's
    `bot.walk(vel.setY(0))`; the trigger — `!BlockRules.isAir(feet+2)` — and the dropped
    `runTaskLater` jump are this port's. Scoped to low ceilings so bots keep their hopping gait in
    the open and in combat. Designed in
    `docs/superpowers/specs/2026-09-14-walk-vs-jump-design.md`.

## Backlog

Nothing new is deferred. Two things this deliberately does **not** do, both of which would be
separate designs: let a `noJump` bot walk while it mines downward, and widen the gait choice to
open ground, which would put the 0.4 clamp somewhere it actually binds.
