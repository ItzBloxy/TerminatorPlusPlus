# Changelog

Notable changes per release. The full history is in the commit log; this is what an operator
upgrading would want to know.

## v5.2.0-ALPHA

**Bots use bows.** A bot can carry a bow alongside its weapon, and holds position to shoot rather
than closing in when closing in is the wrong answer:

```
/tplus create Hunter 5 none netherite diamond minecraft:netherite_sword minecraft:bow
```

Three things make a bot raise it. The target has been off the ground for two seconds — which
covers Phantoms, Ghasts, the Ender Dragon, elytra and creative flight, without naming any of them.
Three squadmates near the target are already towering, so the rest provide fire instead of adding
to the pile. Or the bot has not left its own block column for a second, meaning it is walled in or
digging a tunnel it will never finish.

Any of those, provided the bot has a bow, the target is vulnerable and between 4 and 24 blocks
away, there is line of sight, the bot is on the ground, and fewer than four squadmates are pressed
against it. A bot that fails any of those goes back to chasing on the same tick.

Arrows are infinite, do vanilla damage at a full draw, and are aimed properly: the trajectory is
solved against the real 0.05 gravity and 0.99 drag, and a moving target is led. A bot shooting the
Ender Dragon aims at its head, because vanilla quarters damage to every other part.

**`/tplus bow <item|none>`** arms or disarms every bot. **`/tplus ranged <auto|always|never>`**
overrides the rules. **`/tplus towerquota <n>`** sets how many towering squadmates trigger the
switch. `/tplus info` gains a `Ranged:` line naming the rule that produced the current mode, so
"why is this bot not shooting?" has an answer.

Three things worth knowing before you use it:

- **The `pvp` gamerule now matters.** 26.2 moved it out of `server.properties`, and it gates
  player-owned arrows in two places. With it off, arrows pass straight through players while melee
  keeps working — bots would silently stop being dangerous at range. `/tplus bow` and
  `/tplus ranged` warn you when it is off.
- **Passing a bow as the weapon costs melee damage.** `/tplus create Archer 3 none none none
  minecraft:bow` still works and still shoots, but the 1.8 damage table has no entry for a bow, so
  those bots punch for a quarter heart. Give the bow its own argument to get both. The command says
  so when you do it.
- **Friendly fire is on.** Bots will hit each other. A bot packed among squadmates drops back to
  melee rather than firing into the scrum, and one whose line is blocked holds its shot, but a
  loose arrow in a crowd still lands.

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
