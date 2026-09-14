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
