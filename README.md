# TerminatorPlus++

Server-side player bots for **Minecraft 26.2**, as a **NeoForge** mod. They hunt a target, mine
through walls, tower, clutch out of falls, bridge over lava and fight back.

This is a port of [TerminatorPlus](https://github.com/HorseNuggets/TerminatorPlus), a Paper plugin
whose upstream development paused on 1.21.1. It is **not a successor and not a replacement** — it is
another option, for anyone who wants a version that runs on a current Minecraft. The original is
still the original; this owes it everything, including most of its method bodies.

It is also a personal project. It is documented properly because that is how it gets built, not
because there is a support commitment behind it. Expect rough edges, expect things in the roadmap
below to be missing, and do not run it anywhere you would mind it breaking.

> **Status: 5.0.0-ALPHA.** The agent works and has been played against. The public API module and
> the neural-network AI are not ported yet — see [What is not built](#what-is-not-built-yet).

---

## Install

Server-side only. **Clients connect with vanilla** — nothing to install on their end, no modpack,
no resource pack.

1. A NeoForge **26.2.0.87** server.
2. Drop `tplus-5.0.0-ALPHA.jar` into `mods/`.
3. Start it. `/tplus` is available to operators.

Building it yourself needs **JDK 25**:

```bash
./gradlew build      # compile and run the unit tests
./gradlew jar        # the mod jar, in build/libs/
```

---

## Using it

```
/tplus create Hunter 5 none netherite diamond minecraft:bow
```

Five bots called Hunter1..Hunter5, in netherite armour, carrying diamond tools and a bow. Then pick
what they hunt:

```
/tplus goal nearestvulnerableplayer
```

…and they come for you.

### Creating bots

```
/tplus create <name> [<count> [<playerlist|none> [<armor> [<tools> [<item>]]]]]
```

Arguments fill left to right, so reaching a later one means typing the earlier ones. **`none` is the
filler** for the two tier slots:

| | |
|---|---|
| `/tplus create Bob` | one bot |
| `/tplus create Bob 5` | five, named Bob1..Bob5 |
| `/tplus create "Bot%" 5` | the `%` is where the index goes; quote it |
| `/tplus create Bob 5 playerlist` | also joins the real player list, so `@a` and the tab list reach them |
| `/tplus create Bob 5 none diamond` | diamond armour |
| `/tplus create Bob 5 none diamond netherite` | …and netherite tools |
| `/tplus create Bob 5 none none none minecraft:trident` | just a weapon |

The name is looked up on Mojang's session servers for a skin, so `/tplus create Technoblade` looks
like Technoblade. A name nobody owns gets the default skin.

**Tiers.** Armour takes `none, leather, chain, copper, gold, iron, diamond, netherite`. Tools take
`none, wood, stone, copper, gold, iron, diamond, netherite`. They differ because vanilla does —
there is no chainmail pickaxe and no wooden chestplate — and each slot rejects the other's names
rather than silently equipping nothing.

Omitting the tools argument gives **iron**, which is what bots have always had. Typing `none` gives
**wood**, the floor: there is no bare-handed tier, because break speed now comes from the tool.

### Choosing a target

```
/tplus goal <goal>
```

`nearestvulnerableplayer` (the default), `nearestplayer`, `nearesthostile`, `nearestraider`,
`nearestmob`, `nearestbot`, `nearestbotdiffer`, `nearestbotdifferalpha`, `customlist`, `player`,
`entity`, `none`. `/tplus goal` on its own reports the current one and what it does.

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

```
/tplus mobtarget <true|false>   whether bots retaliate against mobs that hit them
/tplus region <from> <to> [<weightX> <weightY> <weightZ>]
/tplus region                   report it
/tplus region clear
```

A region confines or biases where bots will look for targets. With all three weights at zero it is a
hard boundary; with weights it is a preference.

### Equipment and behaviour

```
/tplus armor <tier>       every bot, all four slots
/tplus tools <tier>       every bot — this is what sets mining speed
/tplus give <item>        every bot's default weapon
/tplus agent <true|false> stop or start the AI entirely
/tplus drops <true|false> whether bots drop their gear on death
/tplus offsets <true|false>  converge on a ring around the target instead of one point
```

### Managing bots

```
/tplus list              names of every loaded bot
/tplus info <name>       position, velocity, health, alive ticks, kills, skin state
/tplus remove <name>
/tplus removeall
```

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
/tplus environment moblisttype [custom|hostile|raider|mob]
```

`addsolid` is for modded blocks the bots would otherwise walk into — it tells the pathing that a
block is real. The mob list is what the `customlist` goal hunts, and `moblisttype` decides whether
it also widens the hostile, raider and mob goals.

Neither list survives a restart. That is upstream's behaviour, kept deliberately.

---

## What is new here

Beyond the port itself, things the Paper plugin did not have:

- **Equipment at spawn.** Armour, tools and a weapon are arguments to `create`, so arming a squad is
  one command instead of three — and two squads can differ, which they could not before.
- **Tool tiers, per bot.** Upstream had one hardcoded iron set for everyone.
- **Break speed follows the tool.** Upstream advanced one crack stage every two ticks, so every
  block took twenty ticks whatever the bot held. It is now the tool's destroy speed: wood 60 ticks a
  block, iron 20 (unchanged, by design), diamond 15, netherite 14, gold 10. Block *hardness* is
  still ignored, as upstream ignored it — obsidian costs what dirt costs.
- **`/tplus enemytarget`.** Upstream could name one player, or a list of mob *types*. There was no
  way to say "that ender dragon".
- **`/tplus tools`**, and both targeting commands now set the goal themselves instead of telling you
  to go and do it.

---

## What is not built yet

Honest about the gaps, because some of them are large. The full list with reasoning is in
[`docs/backlog.md`](docs/backlog.md).

**Not ported yet:**

- **The public API module** — `TerminatorPlusAPI`, `BotManager`, `Terminator`. Deferred on purpose
  until real internal usage could shape it. That usage now exists, so this is next.
- **The neural-network AI** and `/tplus ai`. Around 1,000 lines; the seams where it plugs in are
  left visible.
- **`Debugger`**, and narrowing the shared agent state.

**Never existed, worth having:**

- **Bots cannot use items.** Food, potions, bows and the shield all wait on one missing piece — a
  use-tick. `/tplus bot <name> shield true` puts a shield in the off-hand and it will never be
  raised — the command does not warn you, so consider this the warning.
- **No self-preservation.** Nothing in the codebase branches on health, so bots never retreat,
  disengage, or eat. They regenerate passively and walk into whatever is killing them.
- **No pathfinder.** Worth saying plainly, because it explains a lot of what you will see: there is
  no graph and no cost function. A bot normalises a vector at its target, jumps, and mines whatever
  is in the way. That is why they prefer straight lines, and why one below you mines to your level
  first and then across.
- Bots can seal each other in when several tower in one column.

---

## How it is built

Two branches, sharing history:

| Branch | What it is |
|---|---|
| `master` | This port. All work lands here |
| `paper-original` | The Paper 1.21.1 original, untouched. The reference every translation is checked against |

**Fidelity is the point.** Method bodies are translated, not redesigned. Where upstream is odd, this
is odd in the same way and says so in a comment — a reader who does not know that will "fix" it.
Every deliberate divergence is written down in a register, currently 31 entries.

Tested at six tiers, because each catches what the others cannot: signature checks, 98 unit tests,
143 in-world GameTests, a server run driven over RCON, a real client, and diffing against
`paper-original`. That last pair earns its place — a manual client session once found four bugs that
132 GameTests and three RCON sessions all passed over.

[`CLAUDE.md`](CLAUDE.md) is the working guide: conventions, 26.2 API traps, and what each test tier
is actually for. [`docs/`](docs) has the design spec and the four implementation plans.

---

## Credit and licence

TerminatorPlus was written by [HorseNuggets](https://github.com/HorseNuggets) and contributors. This
is a derivative work and would not exist without it. Bugs here are almost certainly mine and not
theirs.

Licensed under the [Eclipse Public License 2.0](LICENSE), the same as upstream.
