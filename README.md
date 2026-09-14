<div align="center">

# Terminator++

**Server-side player bots for Minecraft 26.2, as a NeoForge mod.**

They hunt a target, mine through walls, tower, clutch out of falls, bridge over lava and fight back.

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-52A535?style=flat-square)](https://www.minecraft.net)
[![NeoForge](https://img.shields.io/badge/NeoForge-26.2.0.87-F16436?style=flat-square)](https://neoforged.net)
[![Java](https://img.shields.io/badge/Java-25-E76F00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![Gradle](https://img.shields.io/badge/Gradle-9.2.1-02303A?style=flat-square&logo=gradle&logoColor=white)](https://gradle.org)
[![Version](https://img.shields.io/badge/version-5.0.0--ALPHA-DFB317?style=flat-square)](#status)
[![Licence](https://img.shields.io/badge/licence-EPL--2.0-0A7BBB?style=flat-square)](LICENSE)

**Vanilla clients connect.** No modpack, no resource pack, nothing on their end.

</div>

---

This is a port of [TerminatorPlus](https://github.com/HorseNuggets/TerminatorPlus) `4.5.1-BETA`, a
Paper plugin whose upstream development paused on 1.21.1. It is **not a successor and not a
replacement** — it is another option, for anyone who wants a version that runs on a current
Minecraft. The original is still the original; this owes it everything, including most of its
method bodies.

It is also a personal project. It is documented properly because that is how it gets built, not
because there is a support commitment behind it. Expect rough edges, expect things in the roadmap
below to be missing, and do not run it anywhere you would mind it breaking.

<a id="status"></a>

> [!WARNING]
> **Status: 5.0.0-ALPHA.** The agent works and has been played against. The public API module and
> the neural-network AI are not ported yet — see [What is not built yet](#what-is-not-built-yet).

## Contents

- [Install](#install)
- [Sixty-second start](#sixty-second-start)
- [Command reference](#command-reference)
  - [Creating bots](#creating-bots)
  - [Choosing a target](#choosing-a-target)
  - [Equipment and behaviour](#equipment-and-behaviour)
  - [Managing bots](#managing-bots)
  - [Driving one bot by hand](#driving-one-bot-by-hand)
  - [Telling bots about your server](#telling-bots-about-your-server)
- [What is new here](#what-is-new-here)
- [What is not built yet](#what-is-not-built-yet)
- [How it is built](#how-it-is-built)
- [Credit and licence](#credit-and-licence)

---

## Install

Server-side only. Clients connect with vanilla.

| | |
|---|---|
| **Server** | NeoForge **26.2.0.87** on Minecraft **26.2** |
| **Client** | Anything. Vanilla 26.2 is fine |
| **Permission** | `/tplus` needs permission level 2 — operators have it by default |

1. Drop `tplus-5.0.0-ALPHA.jar` into `mods/`.
2. Start the server.
3. `/tplus` is available to operators.

### Building it yourself

Needs **JDK 25**. The Gradle wrapper fetches everything else.

```bash
./gradlew build
```

```bash
./gradlew jar
```

`build` compiles and runs the 109 unit tests; `jar` writes `build/libs/tplus-5.0.0-ALPHA.jar`.

<details>
<summary><b>The other run tasks</b></summary>

<br>

| Task | What it does |
|---|---|
| `./gradlew runGameTestServer` | 147 in-world GameTests, headless |
| `./gradlew runServer` | dev server, RCON on 25575 |
| `./gradlew runClient` | dev client, for the things a person has to watch |

`tools/` has an RCON client and the recipe for a **production** NeoForge server test. Read
[`tools/README.md`](tools/README.md) first — the dev run refuses vanilla clients during network
negotiation, so it is not representative of production.

</details>

---

## Sixty-second start

```
/tplus create Hunter 5 none netherite diamond minecraft:bow
```

Five bots called Hunter, in netherite armour, carrying diamond tools and a bow. Then pick what
they hunt:

```
/tplus goal nearestvulnerableplayer
```

…and now they'll target you. When you have had enough:

```
/tplus removeall
```

---

## Command reference

Everything lives under `/tplus`. Several commands report their current value when you leave the
argument off; the tables below say which.

### Creating bots

```
/tplus create <name> [<count> [<playerlist|none> [<armor> [<tools> [<item>]]]]]
```

Arguments fill left to right, so reaching a later one means typing the earlier ones. **`none` is
the filler** for the two tier slots.

| Command | What you get |
|---|---|
| `/tplus create Bob` | one bot |
| `/tplus create Bob 5` | five bots |
| `/tplus create "Bot%" 5` | the `%` is where the index goes — `Bot1`…`Bot5`. Quote it |
| `/tplus create Bob 5 playerlist` | also joins the real player list, so `@a` and the tab list reach them |
| `/tplus create Bob 5 none diamond` | diamond armour |
| `/tplus create Bob 5 none diamond netherite` | …and netherite tools |
| `/tplus create Bob 5 none none none minecraft:trident` | just a weapon |

The name is looked up on Mojang's session servers for a skin, so `/tplus create Technoblade` looks
like Technoblade. A name nobody owns gets the default skin. `count` is capped at **100** per
command.

#### Tiers

| Slot | Accepted | Omitted | `none` |
|---|---|---|---|
| `armor` | `none` `leather` `chain` `copper` `gold` `iron` `diamond` `netherite` | nothing equipped | nothing equipped |
| `tools` | `none` `wood` `stone` `copper` `gold` `iron` `diamond` `netherite` | **iron** | **wood** |

The two lists differ because vanilla does — there is no chainmail pickaxe and no wooden chestplate
— and each slot rejects the other's names rather than silently equipping nothing.

Omitting the tools argument gives **iron**, which is what bots have always had. Typing `none` gives
**wood**, the floor: there is no bare-handed tier, because break speed now comes from the tool.

Every tier also carries **shears**, which are not a tier of their own — vanilla has one pair. They
are what a bot reaches for on leaves, wool and cobweb, the blocks where no pickaxe, axe or shovel
beats bare hands. A leaf block goes from 120 ticks to 8, which is the difference between a bot
crossing a forest and a bot stuck in one.

### Choosing a target

```
/tplus goal <goal>
/tplus goal                     report the current goal and what it does
```

| Goal | Finds |
|---|---|
| `nearestvulnerableplayer` | the nearest real player in Survival or Adventure — **the default** |
| `nearestplayer` | the nearest real player, whatever their gamemode |
| `nearesthostile` | the nearest hostile entity |
| `nearestraider` | the nearest raider |
| `nearestmob` | the nearest mob |
| `nearestbot` | the nearest bot |
| `nearestbotdiffer` | the nearest bot with a different username |
| `nearestbotdifferalpha` | …with a different username once non-alpha characters are stripped |
| `customlist` | only the mob types in the custom list — see [environment](#telling-bots-about-your-server) |
| `player` | one player, set by `playertarget` |
| `entity` | whatever `enemytarget` set |
| `none` | nothing |

Two commands set a target **and** switch the goal for you:

```
/tplus playertarget <player>            one player, by name
/tplus enemytarget generic <type|#tag>  everything of a type, now and later
/tplus enemytarget specific <selector>  exactly the entities matched right now
/tplus enemytarget                      report what is set
/tplus enemytarget clear                clear it, leaving the goal alone
```

The difference between the two `enemytarget` modes is what happens to a zombie that spawns a minute
later. `generic zombie` hunts it; `specific @e[type=zombie]` does not, because that pinned the
zombies that existed when you typed it. `generic` takes tags too, so `#minecraft:raiders` works.

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
/tplus mobtarget <true|false>   whether bots retaliate against mobs that hit them
/tplus mobtarget                report it
/tplus region <from> <to> [<weightX> <weightY> <weightZ>]
/tplus region                   report it
/tplus region clear
```

A region confines or biases where bots will look for targets. It is two block positions, and both
blocks are covered entirely. With the weights omitted — or all three at zero — it is a hard
boundary; with weights it is a preference. The weights come as all three or none, and each must be
zero or more.

### Equipment and behaviour

| Command | Effect | Default |
|---|---|---|
| `/tplus armor <tier>` | every bot, all four slots | — |
| `/tplus tools <tier>` | every bot — **this is what sets mining speed** | iron |
| `/tplus give <item>` | every bot's default weapon | — |
| `/tplus agent <true\|false>` | stop or start the AI entirely | on |
| `/tplus drops <true\|false>` | whether bots drop their gear on death | off |
| `/tplus offsets <true\|false>` | converge on a ring around the target instead of one point | on |
| `/tplus descendrange <blocks>` | how close, horizontally, a stuck bot must be before it tunnels down toward a target below it | 8 |

`agent`, `drops` and `offsets` require their argument — there is no report form.
`descendrange` reports when given none, and takes `unlimited` to lift the cap entirely, which is
what bots did before it existed: one that lost its footing fifty blocks from a target below would
tunnel straight down to its level and then mine across.

**A value below 10 does less than it looks like.** A second, older rule digs down whenever the bot
is more than 10 blocks above its target *and* within 10 horizontally, and that rule is not capped.
So for any drop deeper than 10 blocks the descent starts at 10 whatever you set — 8 and 10 are the
same setting there. `descendrange` only bites below 10 on drops *shallower* than 10 blocks, which
is the one case the older rule cannot reach. Setting it above 10 raises the threshold for
everything.

### Managing bots

| Command | Reports |
|---|---|
| `/tplus list` | names of every loaded bot |
| `/tplus info <name>` | dimension, position, velocity, health, alive ticks, kills, player-list membership, skin state |
| `/tplus remove <name>` | — |
| `/tplus removeall` | — |

### Driving one bot by hand

```
/tplus bot <name> punch | sneak | stand | swim | lookdown | lookup | faceme | hold <item>
/tplus bot <name> shield <true|false>
```

### Telling bots about your server

```
/tplus environment help [blocks|mobs]
/tplus environment getblock <pos>
/tplus environment addsolid <block> | addsolid at <pos>
/tplus environment removesolid <block> | removesolid at <pos>
/tplus environment listsolids | clearsolids
/tplus environment addmob <type> | removemob <type>
/tplus environment listmobs | clearmobs
/tplus environment moblisttype [hostile|raider|mob|custom]
```

`addsolid` is for modded blocks the bots would otherwise walk into — it tells the pathing that a
block is real. The mob list is what the `customlist` goal hunts, and `moblisttype` decides whether
it also widens the hostile, raider and mob goals.

> [!NOTE]
> Neither list survives a restart. That is upstream's behaviour, kept deliberately.

---

## What is new here

Beyond the port itself, things the Paper plugin did not have:

- **Equipment at spawn.** Armour, tools and a weapon are arguments to `create`, so arming a squad is
  one command instead of three — and two squads can differ, which they could not before.
- **Tool tiers, per bot.** Upstream had one hardcoded iron set for everyone.
- **Shears.** Untiered and always present, so a bot cuts through leaves at 8 ticks a block instead
  of 120. Wool and cobweb come along with them.
- **Break speed follows the tool.** Upstream advanced one crack stage every two ticks, so every
  block took twenty ticks whatever the bot held. It is now the tool's destroy speed:

  | Tier | `wood` | `stone` | `copper` | `iron` | `diamond` | `netherite` | `gold` |
  |---|---|---|---|---|---|---|---|
  | **Ticks per block** | 60 | 30 | 24 | **20** | 15 | 14 | 10 |

  Iron is unchanged at twenty, by design, and a GameTest pins that number. Block *hardness* is
  still ignored, as upstream ignored it — obsidian costs what dirt costs.
- **`/tplus enemytarget`.** Upstream could name one player, or a list of mob *types*. There was no
  way to say "that ender dragon".
- **Targets that are not alive.** Upstream's targeting was typed on living entities, so end
  crystals, boats, minecarts, item frames and paintings could not be hunted at all. A bot now
  targets whatever a player could hit. Ender dragons also take full damage rather than a quarter,
  because bots aim at the head like everyone else.
- **`/tplus tools`**, and both targeting commands now set the goal themselves instead of telling you
  to go and do it.

---

## What is not built yet

Honest about the gaps, because some of them are large. The full list with reasoning is in
[`docs/backlog.md`](docs/backlog.md).

<details open>
<summary><b>Not ported yet</b></summary>

<br>

- **The public API module** — `TerminatorPlusAPI`, `BotManager`, `Terminator`. Deferred on purpose
  until real internal usage could shape it. That usage now exists, so this is next.
- **The neural-network AI** and `/tplus ai`. Ten files, a little under 800 lines, plus the `move`
  and `tickBot` branches that read them. The seams where it plugs in are left visible.
- **`Debugger`** — 497 lines, and nothing else depends on it.
- **Narrowing the shared agent state.**

</details>

<details open>
<summary><b>Never existed, worth having</b></summary>

<br>

- **Bots cannot use items.** Food, potions, bows and the shield all wait on one missing piece — a
  use-tick. `/tplus bot <name> shield true` puts a shield in the off-hand and it will never be
  raised — the command does not warn you, so consider this the warning.
- **No self-preservation.** Nothing in the codebase branches on health, so bots never retreat,
  disengage, or eat. They regenerate passively and walk into whatever is killing them. Armour
  changes how long a bot lasts, not what it does.
- **No pathfinder.** Worth saying plainly, because it explains a lot of what you will see: there is
  no graph and no cost function. A bot normalises a vector at its target, jumps, and mines whatever
  is in the way. That is why they prefer straight lines, and why one below you mines to your level
  first and then across.
- **The MLG is a reflex, not a route.** The water clutch runs only once the game has decided a bot
  is falling too fast, so a bot will never deliberately drop to you and clutch.
- Bots can seal each other in when several tower in one column.

</details>

---

## How it is built

### Two branches, sharing history

| Branch | What it is |
|---|---|
| `master` | This port. All work lands here |
| `paper-original` | The Paper 1.21.1 original, untouched. The reference every translation is checked against |

**Fidelity is the point.** Method bodies are translated, not redesigned. Where upstream is odd, this
is odd in the same way and says so in a comment — a reader who does not know that will "fix" it.
Every deliberate divergence is written down in a register, currently 31 entries.

Because the branches share history, the original of any file is one command away:

```bash
git show paper-original:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java
```

### Six tiers of testing

Each catches a class of defect the others cannot.

| Tier | Catches | Blind to |
|---|---|---|
| Signature checks against the patched sources jar | Compile errors, 26.2 renames | Everything else |
| **109 unit tests** (`src/test`) | Pure maths — vectors, offsets, the scheduler | Anything needing a world |
| **147 GameTests** (`src/gametest`) | Integration: mining, clutching, block rules | Anything needing a real client or server runtime |
| `runServer` driven over RCON | Server-runtime crashes, command trees | Anything visual |
| A real client | Rendering, skins, projectile collision, packet ordering | — |
| Diffing against `paper-original` | Silent behaviour drift | — |

That last pair earns its place. A manual client session once found **four** bugs that 132 GameTests
and three RCON sessions all passed over — bots spawning in Creative and so immune to arrows, entity
packets sent before player info, unsigned skins that render nothing, and a missing skin-layer mask.
A green suite is not the same thing as "it works".

### Repository layout

```
src/main/java/net/nuggetmc/tplus/
├── agent/          the AI — targeting, navigation, mining, block scans
│   └── legacy/     the ported LegacyAgent and its collaborators
├── bot/            the bot itself, its registry, profiles and equipment tiers
├── command/        the /tplus Brigadier tree
├── event/          bot lifecycle events
├── motion/         vectors, physics and ground checks
└── util/           skins, items, scheduling, logging

src/test/           the unit tests
src/gametest/       the in-world GameTests
docs/               the design specs, four implementation plans, and the backlog
tools/              an RCON client, and the recipe for a production server test
```

[`CLAUDE.md`](CLAUDE.md) is the working guide: conventions, 26.2 API traps, and what each test tier
is actually for.

---

## Credit and licence

TerminatorPlus was written by [HorseNuggets](https://github.com/HorseNuggets) and contributors. This
is a derivative work and would not exist without it. Bugs here are almost certainly mine and not
theirs.

Licensed under the [Eclipse Public License 2.0](LICENSE), the same as upstream.
