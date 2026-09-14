# Descend range — design

A bot that is far from its target but above it tunnels straight down to the target's Y, then mines
across. It should close the horizontal gap first and only dig down once it is near. This caps the
descent on horizontal distance.

Upstream has no such cap, so this is a deliberate divergence rather than a translation — register
it as deviation 33.

---

## The problem

`Navigation.checkDown` has two ways in. `LegacyAgent.tickBot` gates the whole method on
`bothXZ = withinTargetXZ || sameXZ`, and passes that one merged flag as `sameColumn`:

```java
if (navigation.checkDown(bot, livingTarget.position(), bothXZ)) return;
```

Branch 1 — same column, more than one block above the target, mine underfoot — inherits its
horizontal bound entirely from that flag. Branch 2 has its own bound, `horizontal < 10`, but it
lives in the `else`, so a bot that satisfies branch 1's test never reaches it.

The two halves of `bothXZ` are not the same kind of thing:

- **`withinTargetXZ`** is a proximity test: within 3 blocks on each axis of the aim point.
- **`sameXZ`** is a *stuckness* test. `center()` samples the bot's block column every 20 ticks and
  sets it when the column has not changed. It says nothing about distance.

So branch 1 is horizontally unbounded in practice. A bot fifty blocks out that bumps into a hill
for one second goes `sameXZ`, and from that tick on it mines the block underfoot. Digging keeps it
in the same column, so `sameXZ` stays true and it rides the flag all the way to the target's Y.
`checkDown` then stops firing, `checkSide` takes over, and it mines fifty blocks across.

Stated precisely: **a stuckness signal is standing in for a proximity signal.** The fix is to
require proximity in addition to being stuck, not instead of it.

The naming is the bug's fingerprint, and is worth fixing in the same change. The parameter is
called `sameColumn` and the javadoc opens "the bot is in the same column and more than one block
above the target" — but the argument is `withinTargetXZ || sameXZ`, and neither of those means
"same column". `withinTargetXZ` is a 7×7 box around the aim point; `sameXZ` is a stopwatch. Read
against that javadoc, an unbounded descent looks intentional, which is most of why it survived
four review passes.

The cost argument runs the same way. Fifty blocks out and twenty-five above the target currently
costs 25 down + 50 across = 75 blocks mined. Capped, the bot walks the 42 for free and mines 25.
Even where the surface is impassable and it has to tunnel the whole horizontal run, it is 67.

## The rule

Branch 1 fires when:

```
withinTargetXZ || (sameXZ && horizontal < range)
```

Branch 2 is untouched.

The distance is horizontal — `Math.hypot` on XZ — and is measured against the target's **true**
position, not the aim point. That matches what branch 2 already does and what `tickBot` already
passes in; the asymmetry between `checkDown` taking the true position and `checkUp` taking the
offset aim point is upstream's and is documented at the call site.

### Why the cap binds only on the stuck arm

`withinTargetXZ` is `|floorX(pos) - floorX(target)| <= 3` on each axis, where `target` is the aim
point. A floored difference of 3 means a real difference under 4, so the bot is under
`sqrt(32) ≈ 5.66` from the aim point.

`Bot.offset` is `BotMath.circleOffset(3)`, fixed once per bot. It is **not** a disc, despite the
name: `x` and `z` each take their *own* `Math.random()` scalar, so the sampled region is neither
uniform nor circular. That is upstream's — `MathUtils.circleOffset` is character-for-character the
same — and only the bound matters here. `sqrt(9u₁²cos²θ + 9u₂²sin²θ) ≤ 3` for any `u₁, u₂` in
`[0, 1)`, so the offset is under 3 blocks whatever the shape.

**So `withinTargetXZ` implies a horizontal distance under 8.66 from the true target, always.**
With `/tplus offsets false` the aim point *is* the target and the bound tightens to 5.66, which
does not change the argument.

That is why the cap goes on the stuck arm rather than at the top of the method. A flat guard would
behave identically for any range of 9 or more — `withinTargetXZ` bots pass it anyway — and would
differ only below 8.66, where it would start vetoing bots that `withinTargetXZ` says are close. The
stuck-arm form is the same rule everywhere the two agree and the correct one where they do not.

It also means a flat guard would change nothing about branch 2 at any range: branch 2 already tests
`horizontal < 10` itself.

### The boundary needs no hysteresis

The half that matters is airtight: **digging down does not change horizontal distance**, so a bot
that starts digging keeps qualifying to dig. The descent cannot interrupt itself.

The approach is looser than that, and worth stating honestly rather than overclaiming. Mining
sideways closes the gap along the dominant axis, since `getDirection()` snaps the yaw-to-target to
whichever of the four it leans toward — but `move()` aims at the *aim point*, not the target, and
adds `Math.random()` to every jump. A bot hovering near the boundary can therefore cross it in
both directions over a few ticks.

The consequence is benign: one tick of mining underfoot, one tick of mining sideways, both of them
progress toward the same target. And it is rare, because a bot converging on an aim ring of radius
3 settles around 3 blocks out, far inside any range worth setting. Hysteresis would buy nothing
and would add a second piece of state to a rule that is currently one boolean.

### The fall-through already works

Nothing new is needed to make a refused bot travel sideways. With `checkDown` false, `tickBot`
reaches `checkUp`, which returns false at its **second** guard — `floorY(pos) >= floorY(targetPos)
- 1`, the bot being above its target. The first guard, the distant-target-under-open-sky one, may
or may not fire; it does not matter, because everything ahead of the Y guard is a pure read
(`LevelRules.aboveGround` and `facingBlock` only query the level and the bot's yaw), so `checkUp`
cannot have done anything on its way out.

`tickBot` then reaches `if (bothXZ) sideResult = checkSide(...)`, which runs because `bothXZ` is
still true — the bot is still stuck. `SurroundingScan.checkNearby` faces the target and scans one
step ahead. `bot.getDirection()` is derived from yaw, so that scan is horizontal whatever the
target's Y.

This is why the change is one boolean and not a movement feature.

## The switch

Both candidate behaviours discussed during design reduce to one implementation at two ranges: 10
reproduces a flat cap exactly, 8 is the tighter stuck-arm form. The range is therefore a runtime
setting rather than a constant.

A public `descendRange` field on `LegacyAgent`, beside `offsets`. **It ships at 8**, which is the
value to implement and the value the tests below assume. Whether 8 survives is the one question
this document leaves open, and it is settled by a client session rather than by argument — changing
it afterwards is a one-line edit to a default, not a redesign.

`tickBot` passes it to `checkDown` along with the two flags it already computes, rather than
`Navigation` reading it off shared state. Two reasons: `AgentState` is twelve collections in one
object and the backlog wants it narrowed rather than widened, and keeping the rule's full input in
the signature is what makes the predicate below unit-testable.

```java
public boolean checkDown(Bot bot, Vec3 targetPos, boolean withinTargetXZ, boolean sameXZ, int range)
```

`bothXZ` stays in `tickBot` — `checkUp`'s gate and `checkSide`'s still read it.

### The command

Follows `/tplus region`'s shape: `legacyAgent(ctx)` for the lookup and a no-argument report form.

Not `/tplus offsets`' inline `instanceof`, which silently succeeds when no legacy agent is
installed. That is tolerable for a write-only toggle and wrong for a command that also reports —
it would print a value that governs nothing. `legacyAgent(ctx)` sends "No legacy agent is
installed." and returns null, which is what `showRegion` already does.

```
/tplus descendrange <blocks>      set it
/tplus descendrange unlimited     Integer.MAX_VALUE — upstream behaviour
/tplus descendrange               report it
```

`IntegerArgumentType.integer(0)`. Blocks are whole here and the comparison widens; a decimal would
buy nothing and would print noise in the report. The report prints `unlimited` rather than
2147483647.

A range of 0 is allowed and is coherent rather than degenerate: `horizontal < 0` never holds, so
stuck bots stop digging down entirely and only `withinTargetXZ` bots descend. That is the far end
of the same dial, not a special case, and it needs no guard.

The field outlives everything an operator is likely to do mid-session: `/tplus agent false` calls
`setEnabled` rather than rebuilding the agent, and `registry.reset()` clears bots, not the
`LegacyAgent`. So a range set once holds for the server session, which is what an A/B needs.

`unlimited` exists so the old behaviour is reachable without a rebuild. That makes the A/B a
three-way in one session, and it makes the deviation reversible at runtime, which is the same
courtesy `/tplus offsets` extends to the aim ring.

## Testing

**Unit.** The horizontal gate becomes a static predicate, which puts the whole feature on the
world-free side of the boundary CLAUDE.md draws:

```java
static boolean mayDigDown(boolean withinTargetXZ, boolean sameXZ, double horizontal, int range)
```

It covers the XZ terms only. Branch 1's other condition — `floorY(pos) > floorY(targetPos) + 1`
— stays inline in `checkDown` with the rest of upstream's structure. It is not part of what
changed, and pulling it out would make the predicate look like the whole branch.

There is no `NavigationTest` yet — `src/test` has thirteen files and none of them covers
`Navigation` — so this is a new one. Six cases: near and unstuck, near and stuck, stuck inside the
range, stuck outside it, unstuck and far, and `unlimited`. The case that earns its keep is **near
and outside the range** — the one that pins `withinTargetXZ` bypassing the cap. That property is
the half a later reader is most likely to "simplify" away: the expression looks redundant until
you know the 8.66 bound.

**GameTest.** One test, and it must be a **pair of assertions on the same setup**, not a single
negative. `checkDown` returns false for *three* unrelated reasons: the cap, either free-space ray
reaching the target, or `getStandingOn()` coming back empty. So "the bot did not dig" proves
nothing on its own. The test asserts the identical arrangement digs at `unlimited` and does not at
8, which pins the cap and only the cap — if the `unlimited` half fails, the geometry is wrong, not
the rule.

Call `checkDown` directly rather than ticking. It takes a raw `Vec3` for the target, so no target
entity is needed at all; the conventions' warning about 200-tick position assertions measuring the
walk applies squarely here.

Two setup constraints follow from the other two false-paths. The bot must be **grounded on a solid
block**, or `standing` is empty. And **both rays must be blocked** — the feet ray is free, since a
ray aimed downward from a bot's feet enters the block it is standing on within the first sample,
but the eye ray needs real geometry between the two points, which is what the existing test's 3×3
stone column provides.

**The tall templates are a squeeze.** The suite's two are `9x30x9` and `5x30x5`. A range-8 test
needs more than 8 blocks of horizontal separation, and `9x30x9` does afford `sqrt(8² + 8²) ≈ 11.3`
— but only corner to corner on the diagonal, which leaves nowhere to put the geometry that blocks
the eye ray. Widening it (the suite already uses `15x6x15` in 33 tests) is the cheaper fix than
threading a wall through the one diagonal that is long enough.

`a_bot_mines_down_toward_a_target_far_below` is unaffected, and doubly so. `spawn` uses
`Vec3.atBottomCenterOf`, so the bot sits at `(4.5, 15, 4.5)` and the target at `(4.5, 1, 4.5)` —
a horizontal distance of **0**, inside any range at all. It also satisfies `withinTargetXZ`
whatever the offset draws, so it never consults the cap in the first place.

**Not tested here.** The sideways fall-through. It needs a live target and a long tick run, which
is the exact shape the conventions warn measures the walk rather than the decision, and it is what
the client session verifies well. The client session is also what picks the default.

## Docs

Four places:

1. A comment at the divergence in `checkDown`, per house style — what upstream did, what changed,
   and the 8.66 bound, since the `withinTargetXZ` term reads as redundant without it.
   `checkDown`'s javadoc and its `sameColumn` parameter go with it: both say "same column" for a
   flag that means neither, and leaving them would describe the old rule on top of the new one.
2. Deviation 33 on Plan B's register. That is the list every later plan extends; do not start a new
   one.
3. `README.md`'s **Equipment and behaviour** table. Note that the sentence under it —
   "Those last three require their argument — there is no report form" — becomes wrong once a
   command with a report form joins the table, and needs rewording rather than extending.
4. `docs/backlog.md`. Its pathfinding-visualiser section says bots "mine straight down to your level
   and then across rather than cutting the diagonal". Half of that stops being true: the descent is
   now bounded, though there is still no diagonal and still no pathfinder.

## Deviations to register

33. **`checkDown`'s first branch is capped on horizontal distance.** Upstream gated it on
    `withinTargetXZ || sameXZ` and bounded it horizontally only through the first of those, so a
    stuck bot dug down at any distance. It now requires `withinTargetXZ || (sameXZ && horizontal <
    descendRange)`, default 8. `/tplus descendrange unlimited` restores upstream's behaviour.
    Branch 2's own `horizontal < 10` is untouched.

## Backlog

Nothing new is deferred. The neighbouring entry worth reading alongside this is "A pathfinding and
goal visualiser", which explains why the agent prefers straight lines: there is no graph and no
cost function, only a normalised vector and whatever is in front of the bot. This cap does not
change that — it bounds one of the two straight lines.
