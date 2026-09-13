# TerminatorPlus NeoForge Port — Plan C: The Environment Command

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [x]`) syntax for tracking.

**Goal:** `/tplus environment` — the operator surface for the two lists the agent already reads and
nothing currently writes. After Plan C a server owner can tell bots that a modded block is solid,
and can build the custom mob list that four of the eleven target goals already branch on.

**Why this one next.** Plan B left four bodies of work. This is the smallest, and the only one that
makes *existing* code live rather than adding new behaviour. Right now `TargetGoal.CUSTOM_LIST`
matches nothing, ever, because `Targeting.CUSTOM_MOB_LIST` has no writer — a goal an operator can
select and that silently does nothing is worse than a missing feature, because it looks like it
works. `BlockRules.isSolid` has the same shape: upstream's is `mat.isSolid() ||
SOLID_MATERIALS.contains(mat)` and ours is the first half only.

**Architecture:** one Brigadier subtree under the existing `/tplus` root, plus one mutable set
behind `BlockRules.isSolid`. No new classes. Upstream's 323 lines collapse to roughly 200, because
Brigadier's argument types do three jobs the original did by hand.

**Tech Stack:** Java 25, Gradle 9.2.1, ModDevGradle 2.0.147, NeoForge 26.2.0.87, Minecraft 26.2,
JUnit 6.1.3, NeoForge `testframework` + GameTests.

**Spec:** `docs/superpowers/specs/2026-09-12-terminatorplus-neoforge-port-design.md` — §4.4 defers
`BotEnvironmentCommand` with the AI. This plan un-defers exactly that one file and nothing else
around it.

**Plan A:** `docs/superpowers/plans/2026-09-12-neoforge-port-a-foundation.md` — complete.
**Plan B:** `docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md` — complete as of `f946823`.
Its deviation register is the one this plan extends; do not start a new list.

**Branch:** `neoforge-port`. The Paper 1.21.1 source stays on `master`. Read the original with
`git show master:<path>` — this is the faithfulness oracle and you should use it constantly.

```bash
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/command/commands/BotEnvironmentCommand.java
```

**Reference the patched jar, not a Paper jar.** Verify every vanilla signature against
`build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar`. Every signature quoted in this
plan was checked against it on 2026-09-13; re-check anything you change.

---

## Scope

**In:**

- A mutable solid-block set behind `BlockRules.isSolid`, and the one call site that currently
  bypasses it.
- `/tplus environment` — eleven subcommands, translating upstream's eleven.
- GameTests for the predicate; a server pass for the tree.

**Out, unchanged from the spec and Plan B:**

- The neural-network AI and `/tplus ai`.
- `Debugger` (497 lines).
- The public API module (`Terminator`, `BotManager`, `TerminatorPlusAPI`, `InternalBridge`,
  `AIManager`).
- Narrowing `AgentState`.

**Half of this plan's state already exists.** `Targeting.CUSTOM_MOB_LIST` and
`Targeting.customListMode` are public static and ported (Plan B Task 10), and `CustomListMode` is
ported verbatim. The mob half of this plan is therefore pure command work with no state design in
it. The block half is not, and that is where the decisions are.

---

## What upstream has

Eleven subcommands on a separate `/botenvironment` root. Read it once before starting; it is short
and repetitive, and the repetition is the point — `addSolid` and `removeSolid` are the same
forty lines twice.

| Upstream | Writes | Disposition here |
|---|---|---|
| `help [blocks\|mobs]` | — | **Ported.** The two bodies are the only documentation of why this command exists |
| `getMaterial <x> <y> <z>` | — | **Ported** as `getblock <pos>` |
| `addSolid <material>` / `<x> <y> <z>` | `SOLID_MATERIALS` | **Ported** as `addsolid <block>` and `addsolid at <pos>` |
| `removeSolid <material>` / `<x> <y> <z>` | `SOLID_MATERIALS` | **Ported** as `removesolid <block>` and `removesolid at <pos>` |
| `listSolids` | — | **Ported** as `listsolids` |
| `clearSolids` | `SOLID_MATERIALS` | **Ported** as `clearsolids` |
| `addCustomMob <name>` | `CUSTOM_MOB_LIST` | **Ported** as `addmob <type>` |
| `removeCustomMob <name>` | `CUSTOM_MOB_LIST` | **Ported** as `removemob <type>` |
| `listCustomMobs` | — | **Ported** as `listmobs` |
| `clearCustomMobs` | `CUSTOM_MOB_LIST` | **Ported** as `clearmobs` |
| `mobListType [mode]` | `customListMode` | **Ported** as `moblisttype [mode]` |
| `autofill`, `matches`, `parseDoubleOrRelative`, `isLocationLoaded` | — | **Not ported.** All four are Brigadier's job; see decision 4 |

---

## Decisions

### 1. One command root, not two

Upstream ships `/botenvironment` beside `/bot`. Everything Plan B built lives under `/tplus`, with
one permission gate at the root (`Commands.LEVEL_GAMEMASTERS`). A second root would need its own
gate, its own registration, and its own help. `/tplus environment <sub>` it is.

That makes the subcommand names shorter without losing anything: `addmob` under `environment` is
unambiguous where upstream's `addCustomMob` had to carry the "custom" on a flat root. Recorded as a
rename in the register, not silently done.

### 2. The solid set is static mutable state on `BlockRules`, and that is the least bad option

`BlockRules` is a static utility with a private constructor. `isSolid(BlockState)` is called from
five places across three packages, and none of them has a natural context object to thread:

```bash
grep -rn "BlockRules.isSolid" src/main/java
```

Three options were considered:

1. **A static mutable `Set<Block>` in `BlockRules`** — upstream's shape exactly, one field, no call
   site changes.
2. **A holder injected into each caller** — `GroundCheck` is called from `Bot.tick` via static
   methods, so this means threading a parameter through the physics path for one feature.
3. **A registry-owned set reached through `TerminatorPlus.registry()`** — makes `BlockRules` depend
   on the mod class, which the spec's layering forbids, and `BlockRules` is deliberately testable
   without a registry.

Option 1. The cost is real and must be handled rather than ignored: **static mutable state leaks
between GameTests**, which share a JVM. Every test that adds an override must clear it, and Task 1
adds `BlockRules.clearSolidOverrides()` partly for that reason. It is not cleared by
`BotRegistry.reset()` — upstream's survives `/bot removeall`, and an operator's environment
configuration surviving a bot reset is the right behaviour anyway.

### 3. One call site currently bypasses the predicate — found while planning, fix it first

Upstream calls `LegacyMats.isSolid` from five places. Four of them are ported through
`BlockRules.isSolid`. The fifth is not:

```bash
git show master:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java | sed -n '810,827p'
grep -n "isSolid" src/main/java/net/nuggetmc/tplus/bot/Bot.java
```

Upstream's `Bot.attemptBlockPlace` guards on `LegacyMats.isSolid(block.getType())`; ours guards on
`state.isSolid()`, the raw vanilla predicate. Today those are identical, because the override set is
always empty — which is exactly why nothing caught it. The moment Task 2 ships, a bot would refuse
to place cobblestone into a modded block the operator had declared solid *everywhere except here*,
where it would overwrite it.

This is a latent Plan B defect, not new work, and it is Task 1 so that the set is never live while
a caller ignores it.

`PlayerUtils.findAbove` and `findBottom` are upstream's other two callers and are deliberately not
ported (Plan B correction 6), so they are not call sites.

### 4. Brigadier's argument types delete four hand-written branches

Upstream does four things by hand that the argument types do:

| Upstream | Replacement |
|---|---|
| `Material.getMaterial(name)` plus a null branch | `ResourceArgument.resource(ctx, Registries.BLOCK)` — rejects an unknown id before the handler runs |
| `EntityType.fromName(name)` plus a null branch | `ResourceArgument.resource(ctx, Registries.ENTITY_TYPE)` |
| `parseDoubleOrRelative` — its own `~` handling | `BlockPosArgument.blockPos()` |
| `isLocationLoaded` plus an "is not loaded" message | `BlockPosArgument.getLoadedBlockPos(ctx, name)`, which throws `ERROR_NOT_LOADED` |
| `@Autofill autofill(...)` — 20 lines of tab completion | The argument types suggest their own registries |

That is upstream's `mat == null` branch, its `type == null` branch, its coordinate parser, its chunk
check and its autofill method, all gone. **The behaviour is the same and the error messages are
vanilla's rather than upstream's.** Recorded as a shape change.

Verified present in 26.2:

```bash
J=build/moddev/artifacts/minecraft-patched-26.2.0.87-sources.jar
unzip -p $J net/minecraft/commands/arguments/ResourceArgument.java | grep -nE "resource\(|getResource\("
unzip -p $J net/minecraft/commands/arguments/coordinates/BlockPosArgument.java | grep -n "getLoadedBlockPos"
unzip -p $J net/minecraft/core/registries/Registries.java | grep -nE "> (BLOCK|ENTITY_TYPE) ="
unzip -p $J net/minecraft/core/Holder.java | grep -n "ResourceKey<T> key()"
```

`ResourceArgument.getResource` returns `Holder.Reference<T>`: `.value()` is the `Block` or
`EntityType<?>`, and `.key().identifier()` is the registry id for messages. Note `identifier()`,
not `location()` — the same 26.2 rename that took `ResourceLocation` to `Identifier`, and the one
thing in Plan B Task 25 that failed to compile first time.

### 5. Two forms per subcommand, split by a literal rather than by argument type

Upstream's `addSolid` takes either one argument (a material) or three (a location). Brigadier
tolerates two argument children of different types on one node, but it reports them as an ambiguity
at startup and the resolution order is not obvious to a reader. A literal is clearer:

```
/tplus environment addsolid <block>
/tplus environment addsolid at <pos>
```

### 6. Neither list persists across a restart

Upstream's are plain static collections with no save path, and an operator who adds twenty modded
blocks loses them on restart. **Kept.** Persistence is a feature, not a translation, and adding one
here would be the only place in three plans where this port invented behaviour. Say so in the help
text, which upstream did not.

### 7. Test tier: GameTests for the predicate, a server pass for the tree

Plan B Task 25 established the rule for command work: *"every handler is a registry loop plus a
message, and what needs checking is that the tree parses and the arguments resolve, which only a
real server shows."* That still holds for the eleven handlers.

The predicate is different. `BlockRules.isSolid` feeds `GroundCheck`, `BlockPlacement`,
`BlockScan.tryPreMLG` and now `attemptBlockPlace`, so an override changes how bots walk and build.
That gets GameTests. The spec (§ on testing) already records why `BlockRules` cannot be unit-tested:
block states need a bootstrapped registry.

---

## File structure

| File | Change | Lines |
|---|---|---|
| `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockRules.java` | The override set and its four accessors; `isSolid` consults it | +40 |
| `src/main/java/net/nuggetmc/tplus/bot/Bot.java` | `attemptBlockPlace` routes through `BlockRules.isSolid` | +3 |
| `src/main/java/net/nuggetmc/tplus/command/BotCommands.java` | The `environment` subtree and eleven handlers | +200 |
| `src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java` | Override tests | +60 |
| `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java` | One test for the fixed call site | +25 |

One phase, four tasks. Each ends in a build that passes.

---

# Phase 1: The environment command

Four tasks. Task 1 is the only one with a design decision in it; the other three are translation.

## Task 1: The override set, and the call site that bypasses it

The extension point `BlockRules.isSolid` has been documenting since Plan B's phase 5/6 review, plus
the `attemptBlockPlace` fix from decision 3. Nothing user-visible changes: the set starts empty and
an empty set makes `isSolid` exactly what it is today.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/agent/legacy/BlockRules.java`
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BlockRuleTests.java`
- Test: `src/gametest/java/net/nuggetmc/tplus/gametest/BotActionTests.java`

- [x] **Step 1: Write the failing GameTests**

In `BlockRuleTests`:

```java
    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("an_operator_can_declare_a_block_solid")
    static void an_operator_can_declare_a_block_solid(ExtendedGameTestHelper helper) {
        // A torch is the clearest case: vanilla says it is not solid and never will, and it is
        // the kind of thing a mod ships fifty variants of.
        BlockState torch = Blocks.TORCH.defaultBlockState();

        try {
            helper.assertFalse(BlockRules.isSolid(torch), "vanilla says a torch is not solid");

            helper.assertTrue(BlockRules.addSolid(Blocks.TORCH), "adding it must report a change");
            helper.assertTrue(BlockRules.isSolid(torch), "and the override must take effect");

            helper.assertFalse(BlockRules.addSolid(Blocks.TORCH), "adding it twice must not");
            helper.assertTrue(BlockRules.solidOverrides().contains(Blocks.TORCH), "and it is listed once");

            helper.assertTrue(BlockRules.removeSolid(Blocks.TORCH), "removing it must report a change");
            helper.assertFalse(BlockRules.isSolid(torch), "and vanilla's answer must come back");
        } finally {
            // GameTests share a JVM and this set is static. A test that leaves an override behind
            // changes how every later test's bots walk.
            BlockRules.clearSolidOverrides();
        }

        helper.succeed();
    }

    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("clearing_the_overrides_reports_how_many_went")
    static void clearing_the_overrides_reports_how_many_went(ExtendedGameTestHelper helper) {
        try {
            BlockRules.addSolid(Blocks.TORCH);
            BlockRules.addSolid(Blocks.LEVER);

            helper.assertValueEqual(BlockRules.clearSolidOverrides(), 2, "two overrides went");
            helper.assertTrue(BlockRules.solidOverrides().isEmpty(), "and none are left");
            helper.assertValueEqual(BlockRules.clearSolidOverrides(), 0, "clearing an empty set is a no-op");
        } finally {
            BlockRules.clearSolidOverrides();
        }

        helper.succeed();
    }
```

In `BotActionTests`, next to `attempt_block_place_refuses_to_overwrite_a_solid_block`:

```java
    @GameTest(timeoutTicks = 200)
    @EmptyTemplate(value = "5x5x5", floor = true)
    @TestHolder("attempt_block_place_honours_an_operators_solid_override")
    static void attempt_block_place_honours_an_operators_solid_override(ExtendedGameTestHelper helper) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawn(helper, registry, new BlockPos(1, 1, 1));

        BlockPos target = new BlockPos(3, 1, 1);
        helper.setBlock(target, Blocks.TORCH);

        try {
            BlockRules.addSolid(Blocks.TORCH);
            bot.attemptBlockPlace(helper.absolutePos(target), Blocks.COBBLESTONE, false);

            // Upstream guards this with LegacyMats.isSolid, which consults the override. Guarding
            // with the raw vanilla predicate instead -- which this port did until Plan C -- makes
            // a bot overwrite exactly the blocks an operator asked it to respect.
            helper.assertBlockPresent(Blocks.TORCH, target);
        } finally {
            BlockRules.clearSolidOverrides();
        }

        registry.reset();
        helper.succeed();
    }
```

Match the local `spawn` helper's signature in whichever test class you put this in; `BotActionTests`
has its own.

Run them. The first two fail to compile (no such methods) and the third fails on the assertion.

- [x] **Step 2: Add the set and its accessors**

Replace `BlockRules.isSolid` and its javadoc:

```java
    /**
     * Blocks an operator has declared solid on top of vanilla's own answer.
     *
     * <p>Upstream's {@code LegacyMats.SOLID_MATERIALS}, whose only writer is
     * {@code /botenvironment addSolid}. Mutable static state, deliberately: {@link #isSolid} is
     * called from five places across three packages and none of them has a context object to
     * thread a holder through. See Plan C decision 2 for the two options that were rejected.
     *
     * <p>It is not cleared by {@code BotRegistry.reset}. An operator's environment configuration
     * outliving {@code /tplus removeall} is upstream's behaviour and the right one. It does not
     * survive a restart, which is also upstream's — see Plan C decision 6.
     */
    private static final Set<Block> SOLID_OVERRIDES = new HashSet<>();

    /**
     * Solidity, as upstream meant it.
     *
     * <p>{@code mat.isSolid() || SOLID_MATERIALS.contains(mat)}. The second half exists for
     * hybrid servers: a block a mod adds is not solid as far as vanilla is concerned, so bots
     * walk into it, place water against it and try to stand on it. The operator declares it.
     */
    public static boolean isSolid(BlockState state) {
        return state.isSolid() || SOLID_OVERRIDES.contains(state.getBlock());
    }

    /** @return true when {@code block} was not already declared solid */
    public static boolean addSolid(Block block) {
        return SOLID_OVERRIDES.add(block);
    }

    /** @return true when {@code block} was declared solid and is no longer */
    public static boolean removeSolid(Block block) {
        return SOLID_OVERRIDES.remove(block);
    }

    /** The declared blocks, for listing. Unmodifiable: the four mutators above are the API. */
    public static Set<Block> solidOverrides() {
        return Collections.unmodifiableSet(SOLID_OVERRIDES);
    }

    /** @return how many declarations were dropped */
    public static int clearSolidOverrides() {
        int size = SOLID_OVERRIDES.size();
        SOLID_OVERRIDES.clear();
        return size;
    }
```

Imports: `java.util.Collections`, `java.util.HashSet`.

- [x] **Step 3: Route `attemptBlockPlace` through it**

In `Bot.attemptBlockPlace`:

```java
        // BlockRules.isSolid, not state.isSolid(): upstream's guard here is LegacyMats.isSolid,
        // which consults the operator's solid list. The two agree exactly while that list is
        // empty, which is why this went unnoticed until the list got a writer.
        if (!BlockRules.isSolid(state)) {
```

Add `import net.nuggetmc.tplus.agent.legacy.BlockRules;`. The `bot` package already depends on
`agent.legacy` through `GroundCheck`, so this adds no new edge.

- [x] **Step 4: Run everything**

```bash
./gradlew build && ./gradlew runGameTestServer
```

Expected: 131 GameTests, 84 unit tests, all green. If a *later* test in the batch fails rather than
one of the three new ones, an override leaked — check that every new test clears in a `finally`.

- [x] **Step 5: Commit**

```bash
git add src/main/java/net/nuggetmc/tplus src/gametest/java/net/nuggetmc/tplus
git commit -m "feat: add the operator solid-block list behind BlockRules.isSolid"
```

---

## Task 2: The solid subcommands

Five of the eleven, and the two location forms.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`

- [x] **Step 1: Add the subtree**

In `register`, beside the other `root.then(...)` calls:

```java
        root.then(Commands.literal("environment")
                .then(Commands.literal("getblock")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(BotCommands::getBlock)))
                .then(Commands.literal("addsolid")
                        .then(Commands.argument("block",
                                        ResourceArgument.resource(event.getBuildContext(), Registries.BLOCK))
                                .executes(ctx -> setSolid(ctx, blockArgument(ctx), true)))
                        .then(Commands.literal("at")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> setSolid(ctx, blockAtPosition(ctx), true)))))
                .then(Commands.literal("removesolid")
                        .then(Commands.argument("block",
                                        ResourceArgument.resource(event.getBuildContext(), Registries.BLOCK))
                                .executes(ctx -> setSolid(ctx, blockArgument(ctx), false)))
                        .then(Commands.literal("at")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> setSolid(ctx, blockAtPosition(ctx), false)))))
                .then(Commands.literal("listsolids").executes(BotCommands::listSolids))
                .then(Commands.literal("clearsolids").executes(BotCommands::clearSolids)));
```

Keep it as one `root.then` chain so the whole subtree reads in one place; Task 3 appends its six to
the same chain.

- [x] **Step 2: Add the handlers**

```java
    /**
     * The block at a position, for an operator who can see the thing but not its id.
     *
     * <p>Ported from {@code getMaterial}. Upstream parsed three coordinates itself, handled
     * {@code ~} itself, and checked the chunk itself; {@code getLoadedBlockPos} does all three and
     * fails with vanilla's own message.
     */
    private static int getBlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        Block block = ctx.getSource().getLevel().getBlockState(pos).getBlock();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Block at [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]: ")
                .append(Component.literal(id(block)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(BlockRules.isSolid(block.defaultBlockState())
                        ? " (solid)" : " (not solid)")), false);
        return 1;
    }

    /**
     * Declares a block solid, or stops declaring it.
     *
     * <p>Ported from {@code addSolid} and {@code removeSolid}, which upstream wrote as forty
     * near-identical lines twice. The only differences were the set method and three message
     * strings, so this takes the direction as a parameter.
     */
    private static int setSolid(CommandContext<CommandSourceStack> ctx, Block block, boolean add) {
        boolean changed = add ? BlockRules.addSolid(block) : BlockRules.removeSolid(block);

        if (!changed) {
            ctx.getSource().sendFailure(Component.literal(id(block)
                    + (add ? " is already in the solid list." : " is not in the solid list.")));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                (add ? "Added " : "Removed ") + id(block)
                        + (add ? " to" : " from") + " the solid list."), true);
        return 1;
    }

    private static int listSolids(CommandContext<CommandSourceStack> ctx) {
        Set<Block> blocks = BlockRules.solidOverrides();

        if (blocks.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No blocks have been declared solid."), false);
            return 1;
        }

        String body = blocks.stream().map(BotCommands::id).sorted()
                .collect(Collectors.joining("\n  "));

        ctx.getSource().sendSuccess(() -> Component.literal(
                blocks.size() + " block(s) declared solid:\n  " + body), false);
        return 1;
    }

    private static int clearSolids(CommandContext<CommandSourceStack> ctx) {
        int size = BlockRules.clearSolidOverrides();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared " + size + " block(s) from the solid list."), true);
        return 1;
    }

    private static Block blockArgument(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ResourceArgument.getResource(ctx, "block", Registries.BLOCK).value();
    }

    private static Block blockAtPosition(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        return ctx.getSource().getLevel().getBlockState(pos).getBlock();
    }

    private static String id(Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? block.getName().getString() : key.toString();
    }
```

Imports to add: `net.minecraft.commands.arguments.ResourceArgument`,
`net.minecraft.core.registries.BuiltInRegistries`, `net.minecraft.core.registries.Registries`,
`net.minecraft.resources.Identifier`, `net.minecraft.world.level.block.Block`,
`net.nuggetmc.tplus.agent.legacy.BlockRules`, `java.util.Set`.

`Identifier`, not `ResourceLocation` — 26.2 renamed it, and `Registry.getKey` returns the new name.

- [x] **Step 3: Build and commit**

```bash
./gradlew build
git add src/main/java/net/nuggetmc/tplus/command
git commit -m "feat: add /tplus environment solid-block subcommands"
```

---

## Task 3: The custom mob subcommands

Six of the eleven, and no new state: `Targeting.CUSTOM_MOB_LIST` and `Targeting.customListMode` are
already there and already read by four goals.

```bash
grep -n "CUSTOM_MOB_LIST\|customListMode" src/main/java/net/nuggetmc/tplus/agent/legacy/Targeting.java
```

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`

- [x] **Step 1: Extend the subtree**

Append to the `environment` chain from Task 2:

```java
                .then(Commands.literal("addmob")
                        .then(Commands.argument("type",
                                        ResourceArgument.resource(event.getBuildContext(), Registries.ENTITY_TYPE))
                                .executes(ctx -> setMob(ctx, true))))
                .then(Commands.literal("removemob")
                        .then(Commands.argument("type",
                                        ResourceArgument.resource(event.getBuildContext(), Registries.ENTITY_TYPE))
                                .executes(ctx -> setMob(ctx, false))))
                .then(Commands.literal("listmobs").executes(BotCommands::listMobs))
                .then(Commands.literal("clearmobs").executes(BotCommands::clearMobs))
                .then(Commands.literal("moblisttype")
                        .executes(BotCommands::showMobListType)
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (CustomListMode mode : CustomListMode.values()) {
                                        builder.suggest(mode.name().toLowerCase(Locale.ROOT));
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(BotCommands::setMobListType)))
```

- [x] **Step 2: Add the handlers**

```java
    /**
     * Adds a mob type to the custom list, or takes one out.
     *
     * <p>Ported from {@code addCustomMob} and {@code removeCustomMob}, folded the same way
     * {@link #setSolid} folds its pair.
     *
     * <p>What the list is <i>for</i> depends on {@code moblisttype}: with CUSTOM it is the whole
     * of the {@code CUSTOM_LIST} goal, and with HOSTILE, RAIDER or MOB it is appended to that
     * goal's built-in set. Four of the eleven goals read it.
     */
    private static int setMob(CommandContext<CommandSourceStack> ctx, boolean add)
            throws CommandSyntaxException {
        EntityType<?> type = ResourceArgument.getResource(ctx, "type", Registries.ENTITY_TYPE).value();
        String name = EntityType.getKey(type).toString();

        boolean changed = add
                ? Targeting.CUSTOM_MOB_LIST.add(type)
                : Targeting.CUSTOM_MOB_LIST.remove(type);

        if (!changed) {
            ctx.getSource().sendFailure(Component.literal(name
                    + (add ? " is already in the custom mob list." : " is not in the custom mob list.")));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                (add ? "Added " : "Removed ") + name
                        + (add ? " to" : " from") + " the custom mob list."), true);
        return 1;
    }

    private static int listMobs(CommandContext<CommandSourceStack> ctx) {
        Set<EntityType<?>> types = Targeting.CUSTOM_MOB_LIST;

        if (types.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "The custom mob list is empty. The CUSTOM_LIST goal will find nothing."), false);
            return 1;
        }

        String body = types.stream().map(t -> EntityType.getKey(t).toString()).sorted()
                .collect(Collectors.joining("\n  "));

        ctx.getSource().sendSuccess(() -> Component.literal(
                types.size() + " mob type(s), mode " + Targeting.customListMode
                        + ":\n  " + body), false);
        return 1;
    }

    private static int clearMobs(CommandContext<CommandSourceStack> ctx) {
        int size = Targeting.CUSTOM_MOB_LIST.size();
        Targeting.CUSTOM_MOB_LIST.clear();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Cleared " + size + " mob type(s) from the custom list."), true);
        return 1;
    }

    private static int showMobListType(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "The custom mob list mode is " + Targeting.customListMode
                        + ". Available: " + CustomListMode.listModes()), false);
        return 1;
    }

    private static int setMobListType(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "mode");
        CustomListMode mode = CustomListMode.from(name);

        if (mode == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + name + "' is not a mode. Available: " + CustomListMode.listModes()));
            return 0;
        }

        Targeting.customListMode = mode;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Custom mob list mode is now " + mode + "."), true);
        return 1;
    }
```

Imports to add: `net.minecraft.world.entity.EntityType`,
`net.nuggetmc.tplus.agent.legacy.CustomListMode`, `net.nuggetmc.tplus.agent.legacy.Targeting`,
`java.util.Locale`.

`CustomListMode.from` and `listModes()` are already ported and are exactly what upstream's
`mobListType` used — do not reimplement either.

- [x] **Step 3: Build and commit**

```bash
./gradlew build
git add src/main/java/net/nuggetmc/tplus/command
git commit -m "feat: add /tplus environment custom-mob subcommands"
```

---

## Task 4: Help, verification and the register

- [x] **Step 1: Port the two help bodies**

Upstream's `help blocks` and `help mobs` are the only place the reason for this command is written
down. Keep both, and add the sentence upstream did not write about persistence.

```java
                .then(Commands.literal("help")
                        .executes(ctx -> environmentHelp(ctx, ""))
                        .then(Commands.literal("blocks").executes(ctx -> environmentHelp(ctx, "blocks")))
                        .then(Commands.literal("mobs").executes(ctx -> environmentHelp(ctx, "mobs"))))
```

```java
    /**
     * Why this command exists.
     *
     * <p>Ported from {@code help}. Both bodies are upstream's text, lightly reflowed, plus one
     * sentence upstream should have had: neither list survives a restart.
     */
    private static int environmentHelp(CommandContext<CommandSourceStack> ctx, String topic) {
        String body = switch (topic) {
            case "blocks" -> """
                    Blocks added by mods are not solid as far as vanilla is concerned, so bots walk \
                    into them, place water against them and fail to stand on them.
                      /tplus environment addsolid <block> declares one solid.
                      /tplus environment addsolid at <pos> declares whatever is at that position.
                    The list is not saved and does not survive a restart.""";
            case "mobs" -> """
                    The custom mob list is an operator-defined list of entity types.
                      /tplus environment addmob <type> adds one.
                      /tplus environment moblisttype changes what the list is for: CUSTOM makes it \
                    the whole of the CUSTOM_LIST goal, and HOSTILE, RAIDER or MOB appends it to \
                    that goal's built-in set.
                    The list is not saved and does not survive a restart.""";
            default -> """
                    /tplus environment help blocks - declaring modded blocks solid.
                    /tplus environment help mobs - building the custom mob list.""";
        };

        ctx.getSource().sendSuccess(() -> Component.literal(body), false);
        return 1;
    }
```

- [x] **Step 2: Run everything**

```bash
./gradlew build && ./gradlew runGameTestServer
./gradlew jar && unzip -l build/libs/*.jar | grep -iE "gametest|Test" || echo "clean"
```

- [x] **Step 3: Verify the tree on a running server**

Plan B's RCON client is in the session scratchpad; rewrite it if it is gone — it is forty lines of
socket code and it is the only way to drive these eleven handlers without a client.

```
tplus environment help
tplus environment help blocks
tplus environment getblock 0 64 0
tplus environment addsolid minecraft:torch
tplus environment addsolid minecraft:torch
tplus environment listsolids
tplus environment removesolid minecraft:torch
tplus environment removesolid minecraft:torch
tplus environment addsolid at 0 64 0
tplus environment listsolids
tplus environment clearsolids
tplus environment addmob minecraft:zombie
tplus environment addmob minecraft:not_a_mob
tplus environment listmobs
tplus environment moblisttype
tplus environment moblisttype hostile
tplus environment moblisttype bogus
tplus environment clearmobs
```

Each line is one thing to confirm: help prints; `getblock` reports an id and a solidity; adding
twice reports the duplicate rather than pretending; `at` resolves the block under the console's
position; an unknown entity id is rejected **by Brigadier** before the handler runs, with vanilla's
message; and `moblisttype` with no argument reports rather than fails.

The server log must contain no `Ambiguity` warning for the `environment` node. If it does, decision
5's literal split was not applied somewhere.

- [x] **Step 4: Extend Plan B's deviation register**

Add to the numbered list in
`docs/superpowers/plans/2026-09-12-neoforge-port-b-agent.md`, continuing from 18:

19. `/botenvironment` becomes `/tplus environment`, and the subcommands lose the qualifiers the flat
    root needed: `addCustomMob` is `addmob`, `getMaterial` is `getblock` (Plan C decision 1).
20. Upstream's `mat == null` and `type == null` branches, its `parseDoubleOrRelative`, its
    `isLocationLoaded` check and its `@Autofill` method are all gone, replaced by the argument
    types. Unknown ids are rejected before the handler runs and the message is vanilla's rather
    than upstream's (Plan C decision 4).
21. `addsolid`/`removesolid` take their location form under an `at` literal rather than as a second
    argument shape, so Brigadier reports no ambiguity (Plan C decision 5).

And record the fix, not as a deviation:

> Found while planning Plan C and fixed in its Task 1: `Bot.attemptBlockPlace` guarded on
> `state.isSolid()` where upstream guards on `LegacyMats.isSolid`. Identical while the override
> list was empty, which is why nothing caught it, and wrong the moment the list got a writer.

- [x] **Step 5: Commit**

```bash
git add -A src/main/java/net/nuggetmc/tplus docs/superpowers/plans
git commit -m "feat: complete /tplus environment"
```

---

## What is still not done after this plan

- **The neural-network AI.** `IntelligenceAgent`, `NeuralNetwork`, `BotData`, `BotNode`,
  `NodeConnections`, `BotDataType`, `ActivationType`, `BotAgent`, `BotSituation`,
  `VerticalDisplacement`, the `move` and `tickBot` branches that read them, and `/tplus ai`.
- **`Debugger`** (497 lines).
- **The public API module.** `Terminator`, `BotManager`, `TerminatorPlusAPI`, `InternalBridge`,
  `AIManager`.
- **Narrowing `AgentState`.**
- **Persistence for both lists**, which no plan has proposed and which would be the first invented
  behaviour in this port. If it is ever wanted, it is a config file and a `ServerStartedEvent`
  listener, not a change to either list.
