# TerminatorPlus NeoForge Port — Plan A: Foundation & Bot Lifecycle

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A server-side NeoForge mod for Minecraft 26.2 where an operator can spawn a named, skinned player bot that renders to vanilla clients, obeys gravity, takes fall and combat damage, dies, and despawns cleanly.

**Architecture:** `Bot extends ServerPlayer` with a fake `Connection`, spawned into a `ServerLevel` and made visible by hand-sent clientbound packets. Physics runs on the vanilla entity tick; everything else runs off `ServerTickEvent.Post`. Mutable vector math is kept in a `MotionVec` shim so the later AI translation stays line-for-line faithful.

**Tech Stack:** Java 25, Gradle 9.2.1, ModDevGradle 2.0.147, NeoForge 26.2.0.87, Minecraft 26.2, JUnit 6.1.3, NeoForge `testframework` + GameTests.

**Spec:** `docs/superpowers/specs/2026-09-12-terminatorplus-neoforge-port-design.md`

**Branch:** `master` (called `neoforge-port` until Plan D). The Paper 1.21.1 source stays on `paper-original`. Read any original with
`git show paper-original:<path>` — this is the faithfulness oracle and you should use it constantly.

**Scope:** This is Plan A of two. A bot produced by Plan A stands still.

Plan B ports `LegacyAgent` (movement, targeting, combat, mining) and also picks up four things the
spec lists under v1 that have no consumer until the agent exists:

- The five bot lifecycle events (`BotDamageByPlayerEvent`, `BotDeathEvent`, `BotFallDamageEvent`,
  `BotKilledByPlayerEvent`, `TerminatorLocateTargetEvent`) — every one is consumed by `Agent`.
- `BlockRules` (spec §4.2). Plan A needs only the fence/wall subset, which lives in `GroundCheck`.
- `util/BotLog` (spec §4.4). Plan A logs through `TerminatorPlus.LOGGER` directly; a wrapper with
  one caller is not worth the indirection yet.
- The `GameTestInstance` test infrastructure — see Task 12 for why.

---

## Two spec corrections found while planning

Apply these; the spec predates them.

1. **Advancements leak fix is cheaper than the spec says.** Spec §4.1 says to lift NeoForge's
   `FakePlayerAdvancements`. That class is referenced nowhere in NeoForge outside its own file, and
   `ServerPlayer.advancements` is `private final` with no setter — installing a replacement would
   need an Access Transformer. `PlayerAdvancements.clearTriggers()` is public, so the fix is to call
   it on bot removal (Task 6). No AT, no Mixin, no `BotAdvancements` class.

2. **`Vector.normalize()` on a zero vector yields `NaN,NaN,NaN`** — verified against `paper-api`.
   This is why the original carries `MathUtils.clean()` and `isNotFinite()` guards. `MotionVec` must
   reproduce the NaN exactly. A "safe" normalize that returns zero would make those guards dead code
   and silently change physics.

---

## File Structure

Files created by this plan. One responsibility each; nothing here exceeds ~450 lines.

| File | Responsibility |
|---|---|
| `gradle.properties` | Version pins: Minecraft, NeoForge, mod id/name/version |
| `settings.gradle` | Project name, NeoForge plugin repository |
| `build.gradle` | ModDevGradle, Java 25, run configs, test wiring |
| `src/main/templates/META-INF/neoforge.mods.toml` | Mod metadata (templated from `gradle.properties`) |
| `src/main/java/net/nuggetmc/tplus/TerminatorPlus.java` | `@Mod` entry point; owns the registry and scheduler |
| `src/main/java/net/nuggetmc/tplus/motion/MotionVec.java` | Mutable vector mirroring Bukkit `Vector` |
| `src/main/java/net/nuggetmc/tplus/motion/BotMath.java` | Yaw/pitch/offset/finite helpers (was `MathUtils`) |
| `src/main/java/net/nuggetmc/tplus/motion/BotPhysics.java` | Pure per-tick velocity integration |
| `src/main/java/net/nuggetmc/tplus/motion/GroundCheck.java` | Standing-on and fence/gate detection |
| `src/main/java/net/nuggetmc/tplus/util/TickScheduler.java` | Tick-keyed delayed-task queue with cancellation |
| `src/main/java/net/nuggetmc/tplus/util/MojangSkins.java` | Async skin lookup |
| `src/main/java/net/nuggetmc/tplus/bot/BotConnection.java` | Fake `Connection` |
| `src/main/java/net/nuggetmc/tplus/bot/BotGameProfiles.java` | `GameProfile` factory (it is a record now) |
| `src/main/java/net/nuggetmc/tplus/bot/Bot.java` | The entity: tick, damage, death, removal |
| `src/main/java/net/nuggetmc/tplus/bot/BotFactory.java` | Spawn and client-visibility packets |
| `src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java` | Live-bot set, tick driver, per-bot isolation |
| `src/main/java/net/nuggetmc/tplus/command/BotCommands.java` | Brigadier `/tplus` tree |
| `src/main/java/net/nuggetmc/tplus/gametest/BotLifecycleTests.java` | In-world GameTests |
| `src/test/java/net/nuggetmc/tplus/motion/MotionVecTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/motion/BotMathTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/motion/BotPhysicsTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/util/TickSchedulerTest.java` | Pure |
| `src/test/java/net/nuggetmc/tplus/bot/BotSpawnTest.java` | Server-backed (`EphemeralTestServerProvider`) |

---

## Task 1: NeoForge project scaffold

The old Bukkit tree cannot compile under ModDevGradle, so it is removed here. **It is safe: every
deleted file is intact on `master`** and this plan quotes `git show paper-original:...` whenever it needs one.

**Files:**
- Delete: `TerminatorPlus-API/`, `TerminatorPlus-Plugin/`, `buildSrc/`, `src/`, `build.gradle.kts`, `settings.gradle.kts`
- Create: `gradle.properties`, `settings.gradle`, `build.gradle`
- Create: `src/main/templates/META-INF/neoforge.mods.toml`
- Create: `src/main/java/net/nuggetmc/tplus/TerminatorPlus.java`

- [ ] **Step 1: Remove the Bukkit tree and old Gradle files**

```bash
cd /d/terminator-plus
git rm -r -q TerminatorPlus-API TerminatorPlus-Plugin buildSrc src build.gradle.kts settings.gradle.kts
```

- [ ] **Step 2: Take the Gradle wrapper from the official 26.2 MDK**

The wrapper jar is a binary; do not hand-write it.

```bash
cd /tmp && rm -rf mdk && git clone -q --depth 1 https://github.com/NeoForgeMDKs/MDK-26.2-ModDevGradle.git mdk
cp -r /tmp/mdk/gradle /d/terminator-plus/gradle
cp /tmp/mdk/gradlew /tmp/mdk/gradlew.bat /d/terminator-plus/
```

Confirm Gradle 9.2.1:

```bash
grep distributionUrl /d/terminator-plus/gradle/wrapper/gradle-wrapper.properties
```

Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.2.1-bin.zip`

- [ ] **Step 3: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true

minecraft_version=26.2
minecraft_version_range=[26.2,)
neo_version=26.2.0.87

mod_id=tplus
mod_name=TerminatorPlus
mod_license=EPL-2.0
mod_version=5.0.0-ALPHA
mod_group_id=net.nuggetmc
```

`minecraft_version_range` is open-ended on purpose. Spec §3.2: no hardcoded version gate.

- [ ] **Step 4: Write `settings.gradle`**

```groovy
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = 'https://maven.neoforged.net/releases' }
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}

rootProject.name = 'TerminatorPlus'
```

- [ ] **Step 5: Write `build.gradle`**

No `client` run configuration — this mod is server-side only.

```groovy
plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '2.0.147'
}

version = mod_version
group = mod_group_id
base.archivesName = mod_id

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
}

neoForge {
    version = project.neo_version

    runs {
        server {
            server()
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        gameTestServer {
            type = 'gameTestServer'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        configureEach {
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        "${mod_id}" {
            sourceSet sourceSets.main
        }
    }

    unitTest {
        enable()
        testedMod = mods."${mod_id}"
    }
}

dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:6.1.3'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:6.1.3'
    testImplementation "net.neoforged:testframework:${neo_version}"
}

tasks.named('test', Test) {
    useJUnitPlatform()
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release = 25
}

var generateModMetadata = tasks.register('generateModMetadata', ProcessResources) {
    var replaceProperties = [
        minecraft_version      : minecraft_version,
        minecraft_version_range: minecraft_version_range,
        neo_version            : neo_version,
        mod_id                 : mod_id,
        mod_name               : mod_name,
        mod_license            : mod_license,
        mod_version            : mod_version,
    ]
    inputs.properties replaceProperties
    expand replaceProperties
    from 'src/main/templates'
    into 'build/generated/sources/modMetadata'
}
sourceSets.main.resources.srcDir generateModMetadata
neoForge.ideSyncTask generateModMetadata
```

- [ ] **Step 6: Write `src/main/templates/META-INF/neoforge.mods.toml`**

```toml
modLoader="javafml"
loaderVersion="[1,)"
license="${mod_license}"
issueTrackerURL="https://github.com/HorseNuggets/TerminatorPlus/issues"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="HorseNuggets and contributors"
description='''
Server-side player bots with an emphasis on human-like behavior.
Requires no client-side installation; vanilla clients can connect.
'''

[[dependencies.${mod_id}]]
    modId="neoforge"
    type="required"
    versionRange="[${neo_version},)"
    ordering="NONE"
    side="SERVER"

[[dependencies.${mod_id}]]
    modId="minecraft"
    type="required"
    versionRange="${minecraft_version_range}"
    ordering="NONE"
    side="SERVER"
```

`side="SERVER"` is what declares this server-side only.

- [ ] **Step 7: Write the mod entry point**

`src/main/java/net/nuggetmc/tplus/TerminatorPlus.java`:

```java
package net.nuggetmc.tplus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TerminatorPlus.MOD_ID)
public class TerminatorPlus {

    public static final String MOD_ID = "tplus";
    public static final Logger LOGGER = LoggerFactory.getLogger("TerminatorPlus");

    public TerminatorPlus(IEventBus modBus) {
        LOGGER.info("TerminatorPlus loading");
    }
}
```

- [ ] **Step 8: Verify the build compiles**

```bash
cd /d/terminator-plus && ./gradlew build
```

Expected: `BUILD SUCCESSFUL`. The first run downloads NeoForge and decompiles Minecraft; allow
several minutes.

- [ ] **Step 9: Verify the server starts and loads the mod**

```bash
cd /d/terminator-plus && ./gradlew runServer
```

Expected: the log contains `TerminatorPlus loading`, then `Done (...)! For help, type "help"`.
Type `stop` to exit. If it halts on the EULA, set `eula=true` in `run/eula.txt` and rerun.

- [ ] **Step 10: Commit**

```bash
cd /d/terminator-plus
git add -A
git commit -m "feat: scaffold NeoForge 26.2 server-side mod

Removes the Paper plugin tree (preserved on master) and replaces it with a
ModDevGradle project targeting Minecraft 26.2 on Java 25. Declares side=SERVER
and an open-ended Minecraft version range, per spec 3.2.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: MotionVec

The mutable vector shim. Spec §2.5 — this exists so the Plan B translation of 102 mutation sites
stays line-for-line and cannot silently discard writes.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/motion/MotionVec.java`
- Test: `src/test/java/net/nuggetmc/tplus/motion/MotionVecTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/net/nuggetmc/tplus/motion/MotionVecTest.java`:

```java
package net.nuggetmc.tplus.motion;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MotionVecTest {

    @Test
    void mutatorsChangeTheReceiverAndReturnIt() {
        MotionVec v = new MotionVec(1, 2, 3);
        MotionVec returned = v.add(new MotionVec(1, 1, 1));

        assertSame(v, returned, "mutators must return this, like Bukkit Vector");
        assertEquals(2, v.getX());
        assertEquals(3, v.getY());
        assertEquals(4, v.getZ());
    }

    @Test
    void settersMutateInPlace() {
        MotionVec v = new MotionVec(1, 2, 3);
        v.setX(9).setY(8).setZ(7);

        assertEquals(9, v.getX());
        assertEquals(8, v.getY());
        assertEquals(7, v.getZ());
    }

    @Test
    void subtractAndMultiplyMutate() {
        MotionVec v = new MotionVec(4, 4, 4);
        v.subtract(new MotionVec(1, 2, 3)).multiply(2);

        assertEquals(6, v.getX());
        assertEquals(4, v.getY());
        assertEquals(2, v.getZ());
    }

    @Test
    void normalizeOfZeroVectorProducesNaN() {
        // Verified against org.bukkit.util.Vector: normalize() divides by a zero
        // length and yields NaN,NaN,NaN. The ported AI relies on BotMath.clean()
        // and isNotFinite() catching exactly this. Do NOT "fix" it.
        MotionVec v = new MotionVec(0, 0, 0).normalize();

        assertTrue(Double.isNaN(v.getX()));
        assertTrue(Double.isNaN(v.getY()));
        assertTrue(Double.isNaN(v.getZ()));
    }

    @Test
    void normalizeOfNonZeroVectorGivesUnitLength() {
        MotionVec v = new MotionVec(3, 0, 4).normalize();

        assertEquals(0.6, v.getX(), 1e-9);
        assertEquals(0.0, v.getY(), 1e-9);
        assertEquals(0.8, v.getZ(), 1e-9);
        assertEquals(1.0, v.length(), 1e-9);
    }

    @Test
    void lengthAndDotMatchBukkit() {
        assertEquals(5.0, new MotionVec(3, 0, 4).length(), 1e-9);
        assertEquals(25.0, new MotionVec(3, 0, 4).lengthSquared(), 1e-9);
        assertEquals(32.0, new MotionVec(1, 2, 3).dot(new MotionVec(4, 5, 6)), 1e-9);
    }

    @Test
    void copyIsIndependent() {
        MotionVec original = new MotionVec(1, 2, 3);
        MotionVec copy = original.copy();
        copy.setY(99);

        assertNotSame(original, copy);
        assertEquals(2, original.getY(), "copy must not alias the original");
    }

    @Test
    void convertsToAndFromVec3() {
        MotionVec v = MotionVec.of(new Vec3(1.5, 2.5, 3.5));

        assertEquals(1.5, v.getX());
        Vec3 back = v.toVec3();
        assertEquals(2.5, back.y);
        assertEquals(3.5, back.z);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*MotionVecTest*'
```

Expected: FAIL — compilation error, `package net.nuggetmc.tplus.motion does not exist`.

- [ ] **Step 3: Write the implementation**

`src/main/java/net/nuggetmc/tplus/motion/MotionVec.java`:

```java
package net.nuggetmc.tplus.motion;

import net.minecraft.world.phys.Vec3;

/**
 * A mutable 3-component vector that mirrors {@code org.bukkit.util.Vector} semantics.
 *
 * <p>Vanilla's {@link Vec3} is immutable. The ported AI mutates vectors in place at
 * roughly 100 call sites; translating those to {@code Vec3} would silently discard the
 * results, with no compiler warning. Every mutator here returns {@code this}, exactly
 * like Bukkit's Vector did.
 *
 * <p>{@link #normalize()} deliberately yields NaN for a zero-length vector, matching
 * Bukkit. Callers guard against that with {@code BotMath.clean} / {@code isNotFinite};
 * making it "safe" would turn those guards into dead code and change bot physics.
 */
public final class MotionVec {

    private double x;
    private double y;
    private double z;

    public MotionVec() {
        this(0, 0, 0);
    }

    public MotionVec(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static MotionVec of(Vec3 vec) {
        return new MotionVec(vec.x, vec.y, vec.z);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public MotionVec setX(double x) {
        this.x = x;
        return this;
    }

    public MotionVec setY(double y) {
        this.y = y;
        return this;
    }

    public MotionVec setZ(double z) {
        this.z = z;
        return this;
    }

    public MotionVec add(MotionVec other) {
        this.x += other.x;
        this.y += other.y;
        this.z += other.z;
        return this;
    }

    public MotionVec add(double dx, double dy, double dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        return this;
    }

    public MotionVec subtract(MotionVec other) {
        this.x -= other.x;
        this.y -= other.y;
        this.z -= other.z;
        return this;
    }

    public MotionVec multiply(double factor) {
        this.x *= factor;
        this.y *= factor;
        this.z *= factor;
        return this;
    }

    /** Matches Bukkit: divides by length, so a zero vector becomes NaN. */
    public MotionVec normalize() {
        double length = length();
        this.x /= length;
        this.y /= length;
        this.z /= length;
        return this;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double dot(MotionVec other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public MotionVec copy() {
        return new MotionVec(x, y, z);
    }

    public Vec3 toVec3() {
        return new Vec3(x, y, z);
    }

    @Override
    public String toString() {
        return "MotionVec(" + x + ", " + y + ", " + z + ")";
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*MotionVecTest*'
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/motion/MotionVec.java src/test/java/net/nuggetmc/tplus/motion/MotionVecTest.java
git commit -m "feat: add MotionVec mutable vector shim

Vanilla Vec3 is immutable; the ported AI mutates vectors in place at ~102
sites. MotionVec preserves Bukkit Vector semantics, including NaN from
normalizing a zero vector, which existing guards depend on.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: BotMath

Port of the v1-relevant half of `MathUtils`. Read the original first:
`git show paper-original:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/MathUtils.java`

The neural-network helpers (`generateConnectionValue`, `getBounds`, `getMutationSize`,
`distribution`, `sum`, `min`, `max`, `getMidValue`, `sortByValue`) are **deferred to a later plan** —
do not port them.

`NumberConversions.isFinite` was verified equivalent to `Double.isFinite` across 0, ±finite,
`MAX_VALUE`, `NaN`, and ±Infinity, so the port uses the JDK method.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/motion/BotMath.java`
- Test: `src/test/java/net/nuggetmc/tplus/motion/BotMathTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/net/nuggetmc/tplus/motion/BotMathTest.java`:

```java
package net.nuggetmc.tplus.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BotMathTest {

    @Test
    void yawPitchLookingDueSouth() {
        // +Z is south; Minecraft yaw 0 faces south.
        float[] out = BotMath.fetchYawPitch(new MotionVec(0, 0, 1));

        assertEquals(0.0f, out[0], 1e-4);
        assertEquals(0.0f, out[1], 1e-4);
    }

    @Test
    void yawPitchLookingDueWest() {
        // atan2(-x, z) with x=-1, z=0 gives +pi/2 -> 90 degrees.
        float[] out = BotMath.fetchYawPitch(new MotionVec(-1, 0, 0));

        assertEquals(90.0f, out[0], 1e-4);
    }

    @Test
    void straightUpAndDownAreVerticalSpecialCases() {
        assertEquals(-90.0f, BotMath.fetchYawPitch(new MotionVec(0, 1, 0))[1], 1e-4);
        assertEquals(90.0f, BotMath.fetchYawPitch(new MotionVec(0, -1, 0))[1], 1e-4);
        assertEquals(-90.0f, BotMath.fetchPitch(new MotionVec(0, 1, 0)), 1e-4);
        assertEquals(90.0f, BotMath.fetchPitch(new MotionVec(0, -1, 0)), 1e-4);
    }

    @Test
    void pitchIsNegativeWhenLookingUpwards() {
        // Direction up and forward: pitch = toDegrees(atan(-y / xz)) = -45.
        assertEquals(-45.0f, BotMath.fetchPitch(new MotionVec(0, 1, 1)), 1e-4);
    }

    @Test
    void isNotFiniteDetectsNaNAndInfinity() {
        assertFalse(BotMath.isNotFinite(new MotionVec(1, 2, 3)));
        assertTrue(BotMath.isNotFinite(new MotionVec(Double.NaN, 0, 0)));
        assertTrue(BotMath.isNotFinite(new MotionVec(0, Double.POSITIVE_INFINITY, 0)));
        assertTrue(BotMath.isNotFinite(new MotionVec(0, 0, Double.NEGATIVE_INFINITY)));
    }

    @Test
    void cleanZeroesOnlyTheNonFiniteComponents() {
        MotionVec v = new MotionVec(Double.NaN, 5, Double.POSITIVE_INFINITY);
        BotMath.clean(v);

        assertEquals(0.0, v.getX());
        assertEquals(5.0, v.getY(), "finite components must be left alone");
        assertEquals(0.0, v.getZ());
    }

    @Test
    void circleOffsetStaysInsideRadiusAndIsFlat() {
        for (int i = 0; i < 200; i++) {
            MotionVec v = BotMath.circleOffset(3);

            assertEquals(0.0, v.getY(), "offset must be horizontal");
            assertTrue(v.length() <= 3.0 + 1e-9, "offset escaped radius: " + v);
        }
    }

    @Test
    void squareMatchesBukkitNumberConversions() {
        assertEquals(9.0, BotMath.square(-3), 1e-9);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*BotMathTest*'
```

Expected: FAIL — `cannot find symbol: class BotMath`.

- [ ] **Step 3: Write the implementation**

`src/main/java/net/nuggetmc/tplus/motion/BotMath.java`:

```java
package net.nuggetmc.tplus.motion;

import java.text.DecimalFormat;
import java.util.Random;
import java.util.Set;

/**
 * Vector and angle helpers. Ported from the Paper build's {@code MathUtils}; see
 * {@code git show paper-original:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/MathUtils.java}.
 *
 * <p>Bukkit's {@code NumberConversions.isFinite} was verified equivalent to
 * {@link Double#isFinite}, so the JDK method is used here.
 */
public final class BotMath {

    public static final Random RANDOM = new Random();

    private static final DecimalFormat FORMATTER_1 = new DecimalFormat("0.#");
    private static final DecimalFormat FORMATTER_2 = new DecimalFormat("0.##");

    private BotMath() {
    }

    /** Returns {yaw, pitch} in degrees for a direction vector. */
    public static float[] fetchYawPitch(MotionVec dir) {
        double x = dir.getX();
        double z = dir.getZ();

        float[] out = new float[2];

        if (x == 0.0D && z == 0.0D) {
            out[1] = (float) (dir.getY() > 0.0D ? -90 : 90);
        } else {
            double theta = Math.atan2(-x, z);
            out[0] = (float) Math.toDegrees((theta + 6.283185307179586D) % 6.283185307179586D);

            double xz = Math.sqrt(square(x) + square(z));
            out[1] = (float) Math.toDegrees(Math.atan(-dir.getY() / xz));
        }

        return out;
    }

    public static float fetchPitch(MotionVec dir) {
        double x = dir.getX();
        double z = dir.getZ();

        if (x == 0.0D && z == 0.0D) {
            return (float) (dir.getY() > 0.0D ? -90 : 90);
        }

        double xz = Math.sqrt(square(x) + square(z));
        return (float) Math.toDegrees(Math.atan(-dir.getY() / xz));
    }

    /**
     * A random horizontal offset within radius {@code r}.
     *
     * <p>Note the three separate {@link Math#random()} calls: x and z each get their own
     * radius scalar, so this is not a uniform sample of a disc and x/z are not on the
     * same circle. That is what upstream does, and bot spread depends on the resulting
     * distribution, so it is preserved. Do not "correct" it to a single shared radius.
     */
    public static MotionVec circleOffset(double r) {
        double rad = 2 * Math.random() * Math.PI;

        double x = r * Math.random() * Math.cos(rad);
        double z = r * Math.random() * Math.sin(rad);

        return new MotionVec(x, 0, z);
    }

    public static boolean isNotFinite(MotionVec vec) {
        return !Double.isFinite(vec.getX()) || !Double.isFinite(vec.getY()) || !Double.isFinite(vec.getZ());
    }

    /** Zeroes any non-finite component in place. Guards against {@link MotionVec#normalize()} NaN. */
    public static void clean(MotionVec vec) {
        if (!Double.isFinite(vec.getX())) vec.setX(0);
        if (!Double.isFinite(vec.getY())) vec.setY(0);
        if (!Double.isFinite(vec.getZ())) vec.setZ(0);
    }

    public static <E> E getRandomSetElement(Set<E> set) {
        return set.isEmpty() ? null : set.stream().skip(RANDOM.nextInt(set.size())).findFirst().orElse(null);
    }

    public static double square(double n) {
        return n * n;
    }

    public static double random(double low, double high) {
        return Math.random() * (high - low) + low;
    }

    public static String round1Dec(double n) {
        return FORMATTER_1.format(n);
    }

    public static String round2Dec(double n) {
        return FORMATTER_2.format(n);
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*BotMathTest*'
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/motion/BotMath.java src/test/java/net/nuggetmc/tplus/motion/BotMathTest.java
git commit -m "feat: port MathUtils to BotMath

Ports the v1-relevant half of MathUtils; neural-network helpers stay deferred.
NumberConversions.isFinite verified equivalent to Double.isFinite.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: TickScheduler

Replaces `BukkitScheduler`. Spec §4.2: 27 `runTaskLater` sites depend on this, and cancellation is
required because `Agent.stopAllTasks()` and `Mining.stopMining()` both cancel pending work.

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/util/TickScheduler.java`
- Test: `src/test/java/net/nuggetmc/tplus/util/TickSchedulerTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/net/nuggetmc/tplus/util/TickSchedulerTest.java`:

```java
package net.nuggetmc.tplus.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TickSchedulerTest {

    @Test
    void taskRunsOnTheScheduledTickAndNotBefore() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(3, () -> log.add("fired"));

        scheduler.tick();
        scheduler.tick();
        assertTrue(log.isEmpty(), "must not fire early");

        scheduler.tick();
        assertEquals(List.of("fired"), log);
    }

    @Test
    void zeroDelayRunsOnTheNextTick() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(0, () -> log.add("fired"));
        scheduler.tick();

        assertEquals(List.of("fired"), log);
    }

    @Test
    void tasksDueOnTheSameTickAllRunInSubmissionOrder() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> log.add("a"));
        scheduler.runLater(1, () -> log.add("b"));
        scheduler.runLater(1, () -> log.add("c"));
        scheduler.tick();

        assertEquals(List.of("a", "b", "c"), log);
    }

    @Test
    void aTaskRunsOnlyOnce() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> log.add("fired"));
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertEquals(1, log.size());
    }

    @Test
    void cancelledTaskNeverRuns() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        int id = scheduler.runLater(2, () -> log.add("fired"));
        scheduler.cancel(id);
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertTrue(log.isEmpty());
    }

    @Test
    void cancelAllClearsEverythingPending() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> log.add("a"));
        scheduler.runLater(5, () -> log.add("b"));
        scheduler.cancelAll();
        for (int i = 0; i < 10; i++) scheduler.tick();

        assertTrue(log.isEmpty());
    }

    @Test
    void aTaskScheduledFromInsideATaskRunsOnALaterTick() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> {
            log.add("outer");
            scheduler.runLater(1, () -> log.add("inner"));
        });

        scheduler.tick();
        assertEquals(List.of("outer"), log, "nested task must not run in the same tick");

        scheduler.tick();
        assertEquals(List.of("outer", "inner"), log);
    }

    @Test
    void oneThrowingTaskDoesNotPreventOthersFromRunning() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(1, () -> { throw new IllegalStateException("boom"); });
        scheduler.runLater(1, () -> log.add("survivor"));

        assertDoesNotThrow(scheduler::tick);
        assertEquals(List.of("survivor"), log);
    }

    @Test
    void negativeDelayIsTreatedAsNextTick() {
        TickScheduler scheduler = new TickScheduler();
        List<String> log = new ArrayList<>();

        scheduler.runLater(-5, () -> log.add("fired"));
        scheduler.tick();

        assertEquals(List.of("fired"), log);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*TickSchedulerTest*'
```

Expected: FAIL — `cannot find symbol: class TickScheduler`.

- [ ] **Step 3: Write the implementation**

`src/main/java/net/nuggetmc/tplus/util/TickScheduler.java`:

```java
package net.nuggetmc.tplus.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A tick-keyed delayed-task queue, replacing {@code BukkitScheduler}'s
 * {@code runTaskLater}. Driven once per server tick from {@code BotRegistry}.
 *
 * <p>Not thread-safe by design: every caller runs on the server thread. Work
 * arriving from another thread must be marshalled with {@code server.execute}
 * before touching this class.
 */
public final class TickScheduler {

    // Its own logger rather than TerminatorPlus.LOGGER: this class is unit tested with
    // no game running, and it should not drag the @Mod class into a pure test.
    private static final Logger LOGGER = LoggerFactory.getLogger(TickScheduler.class);

    private record Task(int id, Runnable action) {
    }

    private final TreeMap<Long, List<Task>> queue = new TreeMap<>();
    private final Set<Integer> cancelled = new HashSet<>();

    private long currentTick;
    private int nextId = 1;

    /**
     * Schedules {@code action} to run {@code delayTicks} from now. A delay of zero
     * or less runs on the next tick, never inline.
     *
     * @return an id usable with {@link #cancel(int)}
     */
    public int runLater(long delayTicks, Runnable action) {
        int id = nextId++;
        long due = currentTick + Math.max(1, delayTicks);
        queue.computeIfAbsent(due, k -> new ArrayList<>()).add(new Task(id, action));
        return id;
    }

    public void cancel(int id) {
        cancelled.add(id);
    }

    public void cancelAll() {
        queue.clear();
        cancelled.clear();
    }

    /** Advances one tick and runs everything now due. */
    public void tick() {
        currentTick++;

        // Drain by whole buckets so a task scheduled from inside a task lands on a
        // later tick rather than extending this one.
        List<Task> due = new ArrayList<>();
        Iterator<Map.Entry<Long, List<Task>>> it = queue.headMap(currentTick, true).entrySet().iterator();
        while (it.hasNext()) {
            due.addAll(it.next().getValue());
            it.remove();
        }

        for (Task task : due) {
            if (cancelled.remove(task.id())) {
                continue;
            }
            try {
                task.action().run();
            } catch (Throwable t) {
                // One bad task must not stop the rest, nor the server tick.
                LOGGER.error("Scheduled TerminatorPlus task {} failed", task.id(), t);
            }
        }
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*TickSchedulerTest*'
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/util/TickScheduler.java src/test/java/net/nuggetmc/tplus/util/TickSchedulerTest.java
git commit -m "feat: add TickScheduler replacing BukkitScheduler

Tick-keyed delayed-task queue with cancellation, covering the 27 runTaskLater
sites the agent port depends on. Isolates throwing tasks so one failure cannot
take down the server tick.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: BotConnection, BotGameProfiles, MojangSkins

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/bot/BotConnection.java`
- Create: `src/main/java/net/nuggetmc/tplus/bot/BotGameProfiles.java`
- Create: `src/main/java/net/nuggetmc/tplus/util/MojangSkins.java`

Originals for reference:
- `git show paper-original:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/nms/MockConnection.java`
- `git show paper-original:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/CustomGameProfile.java`
- `git show paper-original:TerminatorPlus-API/src/main/java/net/nuggetmc/tplus/api/utils/MojangAPI.java`

- [ ] **Step 1: Write `BotConnection`**

`src/main/java/net/nuggetmc/tplus/bot/BotConnection.java`:

```java
package net.nuggetmc.tplus.bot;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A {@link Connection} that goes nowhere, so a {@code ServerPlayer} can exist without
 * a socket. Modelled on NeoForge's own {@code FakePlayer.FakeConnection}, which is
 * package-private and cannot be reused.
 *
 * <p>This replaces the Paper build's MockConnection (68 lines) and MockChannel (81
 * lines). Both existed largely to defeat obfuscation: MockConnection reflected on
 * {@code Connection.class.getDeclaredField("q")}. Minecraft 26.1 dropped server
 * obfuscation, so none of that is needed.
 *
 * <p>Unlike NeoForge's FakePlayer, bots here can be added to the real
 * {@code PlayerList}, so the server will genuinely try to send them packets. Every
 * {@code send} is therefore explicitly swallowed.
 */
public final class BotConnection extends Connection {

    public BotConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
        // No handshake will ever arrive.
    }

    @Override
    public void send(Packet<?> packet) {
        // Discarded: there is no client behind this connection.
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public boolean isConnected() {
        return true;
    }
}
```

- [ ] **Step 2: Write `MojangSkins`**

`src/main/java/net/nuggetmc/tplus/util/MojangSkins.java`:

```java
package net.nuggetmc.tplus.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.nuggetmc.tplus.TerminatorPlus;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Looks up a player's skin texture from Mojang's session API.
 *
 * <p>Spec section 7: this performs blocking HTTP and must never run on the server
 * thread. Everything here returns a future; callers resume on the server thread with
 * {@code server.execute}.
 */
public final class MojangSkins {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_URL =
            "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";

    private MojangSkins() {
    }

    /**
     * Resolves {@code name} to a {value, signature} texture pair.
     *
     * @return a future of the pair, or of {@code null} when the player does not exist
     *         or Mojang cannot be reached. Never completes exceptionally.
     */
    public static CompletableFuture<String[]> fetch(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uuid = readJson(UUID_URL + name).get("id").getAsString();

                JsonObject profile = readJson(String.format(SESSION_URL, uuid));
                JsonObject textures = profile.getAsJsonArray("properties").get(0).getAsJsonObject();

                return new String[]{
                        textures.get("value").getAsString(),
                        textures.get("signature").getAsString()
                };
            } catch (Exception e) {
                TerminatorPlus.LOGGER.warn("Could not fetch skin for '{}': {}", name, e.toString());
                return null;
            }
        });
    }

    private static JsonObject readJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<java.io.InputStream> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStreamReader reader = new InputStreamReader(response.body())) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
```

- [ ] **Step 3: Write `BotGameProfiles`**

`src/main/java/net/nuggetmc/tplus/bot/BotGameProfiles.java`:

```java
package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.UUID;

/**
 * Builds {@link GameProfile}s for bots.
 *
 * <p>The Paper build subclassed GameProfile (CustomGameProfile). Since authlib 9.x
 * GameProfile is a {@code final record}, so this is a factory instead. Accessors also
 * moved: {@code getProperties()} is now {@code properties()}.
 */
public final class BotGameProfiles {

    private BotGameProfiles() {
    }

    /**
     * @param skin a {value, signature} texture pair, or null for the default skin
     */
    public static GameProfile create(String name, String[] skin) {
        return create(randomSteveUuid(), name, skin);
    }

    public static GameProfile create(UUID uuid, String name, String[] skin) {
        PropertyMap properties = new PropertyMap();

        if (skin != null && skin.length == 2 && skin[0] != null) {
            properties.put("textures", new Property("textures", skin[0], skin[1]));
        }

        return new GameProfile(uuid, trim16(name), properties);
    }

    /**
     * A random UUID whose hash is even.
     *
     * <p>Ported from {@code BotUtils.randomSteveUUID}. When a profile carries no skin
     * texture, the client picks the default model from the UUID's hash parity: even
     * gives Steve, odd gives Alex. Constraining the hash keeps skinless bots visually
     * consistent instead of randomly alternating. The upstream version recursed; this
     * loops, which is the same thing without the stack.
     */
    public static UUID randomSteveUuid() {
        UUID uuid = UUID.randomUUID();

        while (uuid.hashCode() % 2 != 0) {
            uuid = UUID.randomUUID();
        }

        return uuid;
    }

    /** Minecraft rejects names longer than 16 characters. */
    public static String trim16(String name) {
        return name.length() > 16 ? name.substring(0, 16) : name;
    }
}
```

- [ ] **Step 4: Verify it all compiles**

```bash
cd /d/terminator-plus && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/bot/BotConnection.java src/main/java/net/nuggetmc/tplus/bot/BotGameProfiles.java src/main/java/net/nuggetmc/tplus/util/MojangSkins.java
git commit -m "feat: add fake connection, profile factory, async skin lookup

BotConnection replaces MockConnection + MockChannel (149 lines to ~50); the
obfuscation-defeating reflection is gone because 26.1 unobfuscated the server.
GameProfile is a final record in authlib 9.x, so CustomGameProfile becomes a
factory. Skin lookup returns a future and never blocks the server thread.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: The Bot entity and first visible spawn

The milestone that proves the whole approach. After this task a bot appears in the world.

Original: `git show paper-original:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java`

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`
- Create: `src/main/java/net/nuggetmc/tplus/bot/BotFactory.java`

- [ ] **Step 1: Write the Bot entity**

`src/main/java/net/nuggetmc/tplus/bot/Bot.java`:

```java
package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.motion.MotionVec;

/**
 * A server-side player bot.
 *
 * <p>Extends {@link ServerPlayer} directly rather than NeoForge's {@code FakePlayer}:
 * FakePlayer sets itself invulnerable and no-ops tick(), die() and canHarmPlayer(),
 * all of which a combat bot needs.
 *
 * <p>Ticking has two sources. The level drives {@link #tick()} for physics, and
 * {@code BotRegistry} drives the agent from ServerTickEvent. Plan A leaves the agent
 * out entirely, so a bot only falls and stands.
 */
public class Bot extends ServerPlayer {

    private final MotionVec velocity = new MotionVec();
    private MotionVec oldVelocity = new MotionVec();

    private int aliveTicks;
    private byte groundTicks;
    private byte jumpTicks;
    private byte noFallTicks = 60;

    private boolean inPlayerList;

    public Bot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());

        this.connection = new ServerGamePacketListenerImpl(
                server,
                new BotConnection(),
                this,
                CommonListenerCookie.createInitial(profile, false));
    }

    /**
     * Bots are meant to be indistinguishable from human players; protection and PvP
     * mods commonly skip anything reporting true here, which would break combat.
     * See spec section 9 risk 4.
     */
    @Override
    public boolean isFakePlayer() {
        return false;
    }

    public MotionVec getBotVelocity() {
        return velocity;
    }

    public int getAliveTicks() {
        return aliveTicks;
    }

    public boolean isBotOnGround() {
        return groundTicks != 0;
    }

    public boolean isInPlayerList() {
        return inPlayerList;
    }

    void setInPlayerList(boolean value) {
        this.inPlayerList = value;
    }

    byte getGroundTicks() {
        return groundTicks;
    }

    void setGroundTicks(byte value) {
        this.groundTicks = value;
    }

    byte getJumpTicks() {
        return jumpTicks;
    }

    void setJumpTicks(byte value) {
        this.jumpTicks = value;
    }

    byte getNoFallTicks() {
        return noFallTicks;
    }

    MotionVec getOldVelocity() {
        return oldVelocity;
    }

    void setOldVelocity(MotionVec value) {
        this.oldVelocity = value;
    }

    /** Applies the current velocity to the entity. Called from the physics step. */
    void applyMotion(double x, double y, double z) {
        this.move(MoverType.SELF, new Vec3(x, y, z));
    }

    /**
     * Keeps the 3x3 chunk neighbourhood around the bot loaded.
     *
     * <p>The Paper build wrote {@code chunk.loaded = true} directly; that field is
     * private as of 26.2 and {@code setLoaded} is the replacement.
     */
    void loadChunks() {
        int cx = chunkPosition().x;
        int cz = chunkPosition().z;

        for (int i = cx - 1; i <= cx + 1; i++) {
            for (int j = cz - 1; j <= cz + 1; j++) {
                level().getChunk(i, j).setLoaded(true);
            }
        }
    }

    /**
     * Removes the bot from the world and from clients.
     *
     * <p>clearTriggers() is the fix for NeoForge issue 1487: a fake player whose UUID
     * matches no real account leaves criterion listeners registered forever. NeoForge
     * ships FakePlayerAdvancements for this, but ServerPlayer.advancements is private
     * and final, so calling the public cleanup is the cheaper route.
     */
    public void removeBot() {
        BotFactory.despawn(this);

        getAdvancements().clearTriggers();

        // ServerPlayer.server is private in 26.2; Entity.getServer() is the public route.
        if (isInPlayerList()) {
            getServer().getPlayerList().getPlayers().remove(this);
            setInPlayerList(false);
        }

        remove(RemovalReason.DISCARDED);
    }

    void incrementAliveTicks() {
        aliveTicks++;
    }

    void decrementTimers() {
        if (jumpTicks > 0) --jumpTicks;
        if (noFallTicks > 0) --noFallTicks;
    }
}
```

- [ ] **Step 2: Write BotFactory**

`src/main/java/net/nuggetmc/tplus/bot/BotFactory.java`:

```java
package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Spawns bots and makes them visible to real clients.
 *
 * <p>Visibility is manual: a bot added with {@code addFreshEntity} is a player entity
 * the vanilla tracker will not announce to clients as a player, so the tab-list entry
 * and spawn packet are sent by hand. Skins ride along in the GameProfile, which is why
 * no client mod is needed.
 */
public final class BotFactory {

    private BotFactory() {
    }

    /**
     * @param addToPlayerList when true the bot joins the real PlayerList, so the server
     *                        treats it as an online player. That path is riskier (spec
     *                        section 9 risk 1); callers should default to false.
     */
    public static Bot spawn(ServerLevel level, Vec3 pos, float yaw, float pitch,
                            GameProfile profile, boolean addToPlayerList) {
        MinecraftServer server = level.getServer();

        Bot bot = new Bot(server, level, profile);
        bot.setPos(pos.x, pos.y, pos.z);
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);

        if (addToPlayerList) {
            server.getPlayerList().getPlayers().add(bot);
            bot.setInPlayerList(true);
            level.addNewPlayer(bot);
            broadcast(bot, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot)));
        } else {
            level.addFreshEntity(bot);
            broadcast(bot, new ClientboundPlayerInfoUpdatePacket(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, bot));
        }

        render(bot);
        return bot;
    }

    /** Sends the packets a client needs in order to draw this bot. */
    public static void render(Bot bot) {
        broadcast(bot, new ClientboundAddEntityPacket(
                bot.getId(),
                bot.getUUID(),
                bot.getX(), bot.getY(), bot.getZ(),
                bot.getXRot(), bot.getYRot(),
                bot.getType(),
                0,
                bot.getDeltaMovement(),
                bot.getYHeadRot()));

        // getNonDefaultValues() replaces the Paper build's NMSUtils, which reflected on
        // a private Int2ObjectMap that no longer exists in 26.2.
        broadcast(bot, new ClientboundSetEntityDataPacket(
                bot.getId(), bot.getEntityData().getNonDefaultValues()));

        broadcast(bot, new ClientboundRotateHeadPacket(bot, (byte) (bot.getYHeadRot() * 256f / 360f)));
    }

    /** Removes the bot from clients: entity first, then the tab-list entry. */
    public static void despawn(Bot bot) {
        broadcast(bot, new ClientboundRemoveEntitiesPacket(bot.getId()));
        broadcast(bot, new ClientboundPlayerInfoRemovePacket(List.of(bot.getUUID())));
    }

    /** Sends a packet to every real player on the server, skipping bots. */
    public static void broadcast(Bot source, Packet<?> packet) {
        MinecraftServer server = source.level().getServer();
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof Bot) {
                continue;
            }
            player.connection.send(packet);
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

```bash
cd /d/terminator-plus && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/bot/Bot.java src/main/java/net/nuggetmc/tplus/bot/BotFactory.java
git commit -m "feat: add Bot entity and spawn factory

Bot extends ServerPlayer directly rather than NeoForge FakePlayer, which
force-enables invulnerability and no-ops tick/die. BotFactory sends the
visibility packets by hand; NMSUtils reflection is replaced with the public
getNonDefaultValues(). isFakePlayer() returns false per spec risk 4.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: BotPhysics and GroundCheck

The velocity integration lifted out of `Bot.updateLocation`, `checkGround`, and
`checkStandingOn` so it can be tested without a world.

Original: `git show paper-original:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/bot/Bot.java`
(see `updateLocation` and `addFriction`).

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/motion/BotPhysics.java`
- Create: `src/main/java/net/nuggetmc/tplus/motion/GroundCheck.java`
- Test: `src/test/java/net/nuggetmc/tplus/motion/BotPhysicsTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/net/nuggetmc/tplus/motion/BotPhysicsTest.java`:

```java
package net.nuggetmc.tplus.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BotPhysicsTest {

    @Test
    void frictionScalesHorizontalOnlyAndLeavesYAlone() {
        MotionVec v = new MotionVec(1.0, 5.0, -2.0);
        BotPhysics.addFriction(v, 0.5);

        assertEquals(0.5, v.getX(), 1e-9);
        assertEquals(5.0, v.getY(), 1e-9, "friction must not touch Y");
        assertEquals(-1.0, v.getZ(), 1e-9);
    }

    @Test
    void frictionSnapsTinyHorizontalComponentsToZero() {
        // Below 0.01 the original clamps to exactly zero rather than decaying forever.
        MotionVec v = new MotionVec(0.005, 0, -0.009);
        BotPhysics.addFriction(v, 0.5);

        assertEquals(0.0, v.getX());
        assertEquals(0.0, v.getZ());
    }

    @Test
    void groundedBotHasVerticalMotionZeroedAndFrictionApplied() {
        MotionVec v = new MotionVec(1.0, -0.5, 0);
        double y = BotPhysics.step(v, /*groundTicks*/ (byte) 3, /*jumpTicks*/ (byte) 0, /*inWater*/ false);

        assertEquals(0.0, y);
        assertEquals(0.0, v.getY());
        assertEquals(0.5, v.getX(), 1e-9, "ground friction is 0.5");
    }

    @Test
    void airborneBotAccumulatesGravity() {
        MotionVec v = new MotionVec(0, 0, 0);
        double y = BotPhysics.step(v, (byte) 0, (byte) 0, false);

        assertEquals(0.0, y, "the returned y is the pre-gravity value");
        assertEquals(-0.08, v.getY(), 1e-9, "gravity is subtracted for the next tick");
    }

    @Test
    void fallSpeedIsClampedAtTerminalVelocity() {
        MotionVec v = new MotionVec(0, -3.49, 0);
        BotPhysics.step(v, (byte) 0, (byte) 0, false);

        assertEquals(-3.5, v.getY(), 1e-9, "must not fall faster than -3.5");
    }

    @Test
    void gravityIsSuppressedDuringTheJumpWindow() {
        // jumpTicks starts at 4; while jumpTicks - 3 > 0 the upward impulse is preserved.
        MotionVec v = new MotionVec(0, 0.42, 0);
        BotPhysics.step(v, (byte) 0, (byte) 4, false);

        assertEquals(0.42, v.getY(), 1e-9, "gravity must not eat the jump impulse");
    }

    @Test
    void waterGivesBuoyancyAndStrongerDrag() {
        MotionVec v = new MotionVec(1.0, -1.0, 0);
        double y = BotPhysics.step(v, (byte) 0, (byte) 0, true);

        assertEquals(-0.9, y, 1e-9, "min(vy + 0.1, 0.1)");
        assertEquals(-0.9, v.getY(), 1e-9);
        assertEquals(0.8, v.getX(), 1e-9, "water friction is 0.8");
    }

    @Test
    void buoyancyIsCappedSoBotsDoNotRocketOutOfWater() {
        MotionVec v = new MotionVec(0, 5.0, 0);
        double y = BotPhysics.step(v, (byte) 0, (byte) 0, true);

        assertEquals(0.1, y, 1e-9);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*BotPhysicsTest*'
```

Expected: FAIL — `cannot find symbol: class BotPhysics`.

- [ ] **Step 3: Write BotPhysics**

`src/main/java/net/nuggetmc/tplus/motion/BotPhysics.java`:

```java
package net.nuggetmc.tplus.motion;

/**
 * Per-tick velocity integration, extracted from the Paper build's
 * {@code Bot.updateLocation} and {@code Bot.addFriction} so it can be tested without a
 * world. Constants are reproduced exactly; do not tune them here.
 */
public final class BotPhysics {

    private static final double GRAVITY = 0.08;
    private static final double TERMINAL_VELOCITY = -3.5;
    private static final double GROUND_FRICTION = 0.5;
    private static final double WATER_FRICTION = 0.8;
    private static final double WATER_BUOYANCY = 0.1;
    private static final double FRICTION_MIN = 0.01;

    private BotPhysics() {
    }

    /**
     * Advances {@code velocity} one tick in place.
     *
     * @return the Y displacement to apply to the entity this tick, which is not always
     *         {@code velocity.getY()} — on the ground it is zeroed, and in air the
     *         stored Y is the value for the <em>next</em> tick.
     */
    public static double step(MotionVec velocity, byte groundTicks, byte jumpTicks, boolean inWater) {
        BotMath.clean(velocity);

        double y;

        if (inWater) {
            y = Math.min(velocity.getY() + WATER_BUOYANCY, WATER_BUOYANCY);
            addFriction(velocity, WATER_FRICTION);
            velocity.setY(y);
        } else if (groundTicks != 0) {
            velocity.setY(0);
            addFriction(velocity, GROUND_FRICTION);
            y = 0;
        } else {
            y = velocity.getY();
            if (jumpTicks - 3 <= 0) {
                velocity.setY(Math.max(y - GRAVITY, TERMINAL_VELOCITY));
            }
        }

        return y;
    }

    /** Scales the horizontal components, snapping near-zero values to exactly zero. */
    public static void addFriction(MotionVec velocity, double factor) {
        double x = velocity.getX();
        double z = velocity.getZ();

        velocity.setX(Math.abs(x) < FRICTION_MIN ? 0 : x * factor);
        velocity.setZ(Math.abs(z) < FRICTION_MIN ? 0 : z * factor);
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*BotPhysicsTest*'
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Write GroundCheck**

This one queries the world and consults block tags, so it is exercised by the server-backed tests
in Task 11 rather than by pure unit tests. See Task 12 for why not GameTests.

`src/main/java/net/nuggetmc/tplus/motion/GroundCheck.java`:

```java
package net.nuggetmc.tplus.motion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out which blocks a bot is standing on.
 *
 * <p>Ported from {@code Bot.checkStandingOn}. The Paper original tested a hand-written
 * Material list; this uses block tags and collision shapes instead. Tag membership is
 * unavailable without a loaded datapack, so this class is covered by GameTests rather
 * than pure unit tests (spec section 6).
 */
public final class GroundCheck {

    /** How far below the feet to probe for a supporting block. */
    private static final double PROBE_DEPTH = 0.01;

    /** Fences and walls are 1.5 blocks tall for collision but 1.0 for their shape. */
    private static final double FENCE_EXTRA_HEIGHT = 1.5;

    private GroundCheck() {
    }

    /**
     * @return supporting block positions, nearest horizontally first; empty when airborne
     */
    public static List<BlockPos> standingOn(ServerLevel level, AABB box, double bbHeight) {
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};

        double feetY = box.minY;
        AABB botBox = new AABB(box.minX, feetY - PROBE_DEPTH, box.minZ,
                box.maxX, feetY + bbHeight, box.maxZ);

        List<BlockPos> found = new ArrayList<>();

        for (double x : xs) {
            for (double z : zs) {
                BlockPos pos = BlockPos.containing(x, feetY - PROBE_DEPTH, z);
                if (found.contains(pos)) {
                    continue;
                }

                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }

                AABB blockBox = state.getCollisionShape(level, pos).bounds().move(pos);
                if (botBox.intersects(blockBox)) {
                    found.add(pos);
                }
            }
        }

        // Fences, walls and gates support a bot standing 0.5 above their block.
        for (double x : xs) {
            for (double z : zs) {
                BlockPos pos = BlockPos.containing(x, feetY - 0.51, z);
                if (found.contains(pos)) {
                    continue;
                }

                // 26.2 removed the single-argument BlockState.is(TagKey). BlockStateBase now
                // implements TypedInstance<Block>, so tag membership goes through the holder.
                BlockState state = level.getBlockState(pos);
                Holder<Block> holder = state.typeHolder();
                if (!holder.is(BlockTags.FENCES) && !holder.is(BlockTags.FENCE_GATES) && !holder.is(BlockTags.WALLS)) {
                    continue;
                }

                AABB shape = state.getCollisionShape(level, pos).bounds().move(pos);
                AABB tall = new AABB(shape.minX, shape.minY, shape.minZ,
                        shape.maxX, shape.minY + FENCE_EXTRA_HEIGHT, shape.maxZ);

                if (botBox.intersects(tall)) {
                    found.add(pos);
                }
            }
        }

        double cx = (box.minX + box.maxX) / 2;
        double cz = (box.minZ + box.maxZ) / 2;
        found.sort((a, b) -> Double.compare(horizSqDist(a, cx, cz), horizSqDist(b, cx, cz)));

        return found;
    }

    private static double horizSqDist(BlockPos pos, double x, double z) {
        double dx = pos.getX() + 0.5 - x;
        double dz = pos.getZ() + 0.5 - z;
        return dx * dx + dz * dz;
    }
}
```

- [ ] **Step 6: Verify it compiles and all tests still pass**

```bash
cd /d/terminator-plus && ./gradlew build
```

Expected: `BUILD SUCCESSFUL`, 25 tests passing.

- [ ] **Step 7: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/motion/BotPhysics.java src/main/java/net/nuggetmc/tplus/motion/GroundCheck.java src/test/java/net/nuggetmc/tplus/motion/BotPhysicsTest.java
git commit -m "feat: extract bot physics and ground detection

Velocity integration lifted out of Bot.updateLocation into a pure function so
gravity, friction, terminal velocity, the jump window and buoyancy are unit
tested. GroundCheck replaces the hand-written Material list with block tags,
so it needs a loaded datapack and is covered by GameTests instead.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: BotRegistry and server tick wiring

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java`
- Modify: `src/main/java/net/nuggetmc/tplus/TerminatorPlus.java`

- [ ] **Step 1: Write BotRegistry**

`src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java`:

```java
package net.nuggetmc.tplus.bot;

import net.minecraft.server.MinecraftServer;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.util.TickScheduler;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live bots and drives the per-tick work that is not the entity tick.
 *
 * <p>Spec section 7: each bot's tick is isolated, so one misbehaving bot cannot take
 * down the server tick. A bot that throws on three consecutive ticks is evicted.
 */
public final class BotRegistry {

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final Set<Bot> bots = ConcurrentHashMap.newKeySet();
    private final Map<Bot, Integer> failures = new HashMap<>();
    private final TickScheduler scheduler = new TickScheduler();

    public TickScheduler scheduler() {
        return scheduler;
    }

    public Collection<Bot> bots() {
        return Set.copyOf(bots);
    }

    public int size() {
        return bots.size();
    }

    public void add(Bot bot) {
        bots.add(bot);
    }

    public void remove(Bot bot) {
        bots.remove(bot);
        failures.remove(bot);
    }

    public Bot byName(String name) {
        for (Bot bot : bots) {
            if (bot.getGameProfile().name().equalsIgnoreCase(name)) {
                return bot;
            }
        }
        return null;
    }

    /** Called once per server tick. */
    public void tick() {
        scheduler.tick();

        for (Bot bot : List.copyOf(bots)) {
            if (!bot.isAlive() && bot.isRemoved()) {
                remove(bot);
                continue;
            }

            try {
                tickBot(bot);
                failures.remove(bot);
            } catch (Throwable t) {
                int count = failures.merge(bot, 1, Integer::sum);
                TerminatorPlus.LOGGER.error("Bot '{}' failed its tick ({}/{})",
                        bot.getGameProfile().name(), count, MAX_CONSECUTIVE_FAILURES, t);

                if (count >= MAX_CONSECUTIVE_FAILURES) {
                    TerminatorPlus.LOGGER.error("Evicting bot '{}' after {} consecutive failures",
                            bot.getGameProfile().name(), count);
                    safeRemove(bot);
                }
            }
        }
    }

    /**
     * Agent hook. Plan A has no agent, so a bot only does what its entity tick does.
     * Plan B dispatches to LegacyAgent here.
     */
    private void tickBot(Bot bot) {
    }

    private void safeRemove(Bot bot) {
        remove(bot);
        try {
            bot.removeBot();
        } catch (Throwable t) {
            TerminatorPlus.LOGGER.error("Failed to clean up bot '{}'", bot.getGameProfile().name(), t);
        }
    }

    /** Removes every bot. Called on server shutdown. */
    public void reset() {
        for (Bot bot : List.copyOf(bots)) {
            safeRemove(bot);
        }
        bots.clear();
        failures.clear();
        scheduler.cancelAll();
    }

    /** Runs {@code action} on the server thread, whether or not the caller is on it. */
    public static void onServerThread(MinecraftServer server, Runnable action) {
        if (server.isSameThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }
}
```

- [ ] **Step 2: Wire the registry into the mod entry point**

Replace `src/main/java/net/nuggetmc/tplus/TerminatorPlus.java` with:

```java
package net.nuggetmc.tplus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.nuggetmc.tplus.bot.BotRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TerminatorPlus.MOD_ID)
public class TerminatorPlus {

    public static final String MOD_ID = "tplus";
    public static final Logger LOGGER = LoggerFactory.getLogger("TerminatorPlus");

    private static final BotRegistry REGISTRY = new BotRegistry();

    /**
     * FML injects the parameters by type. The two-argument form is what the official
     * 26.2 MDK uses; do not reduce it to a single argument.
     */
    public TerminatorPlus(IEventBus modEventBus, ModContainer modContainer) {
        // Game events (tick, commands, shutdown) live on the NeoForge bus, not the mod bus.
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("TerminatorPlus loading");
    }

    public static BotRegistry registry() {
        return REGISTRY;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        REGISTRY.tick();
    }

    // The RegisterCommandsEvent handler is added in Task 10, once BotCommands exists.

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Removing {} bot(s) on shutdown", REGISTRY.size());
        REGISTRY.reset();
    }
}
```

Handlers are **instance** methods registered with `NeoForge.EVENT_BUS.register(this)`, matching the
MDK. Do not use `@EventBusSubscriber` with static handlers here.

- [ ] **Step 3: Verify it compiles**

```bash
cd /d/terminator-plus && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/bot/BotRegistry.java src/main/java/net/nuggetmc/tplus/TerminatorPlus.java
git commit -m "feat: add BotRegistry and server tick wiring

Drives the scheduler and per-bot work from ServerTickEvent.Post, with each bot
tick isolated so one failure cannot take down the server tick; three consecutive
failures evict the bot. Bots are cleaned up on ServerStoppingEvent.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 9: Bot ticking, damage and death

Wires physics into the entity tick and ports the damage path. Note `hurt` became
`hurtServer(ServerLevel, DamageSource, float)` in 26.2.

**Files:**
- Modify: `src/main/java/net/nuggetmc/tplus/bot/Bot.java`

- [ ] **Step 1: Add the tick, damage and removal methods to Bot**

Insert these into `Bot`, immediately before the closing brace. Also add the imports listed at the
top of the block.

```java
// Add to the existing imports at the top of Bot.java:
//   import net.minecraft.core.BlockPos;
//   import net.minecraft.util.Mth;
//   import net.minecraft.world.damagesource.DamageSource;
//   import net.minecraft.world.entity.Entity;
//   import net.minecraft.world.level.block.Block;
//   import net.minecraft.world.level.block.Blocks;
//   import net.minecraft.world.level.block.state.BlockState;
//   import net.minecraft.world.level.block.state.properties.BlockStateProperties;
//   import net.minecraft.world.phys.AABB;
//   import net.nuggetmc.tplus.TerminatorPlus;
//   import net.nuggetmc.tplus.motion.BotMath;
//   import net.nuggetmc.tplus.motion.BotPhysics;
//   import net.nuggetmc.tplus.motion.GroundCheck;
//   import java.util.List;
//   import java.util.Set;

    private static final float REGEN_PER_TICK = 0.025f;

    private List<BlockPos> standingOn = List.of();
    private boolean removeOnDeath = true;

    @Override
    public void tick() {
        loadChunks();

        super.tick();

        if (!isAlive()) {
            return;
        }

        incrementAliveTicks();
        decrementTimers();

        if (checkGround()) {
            if (getGroundTicks() < 5) {
                setGroundTicks((byte) (getGroundTicks() + 1));
            }
        } else {
            setGroundTicks((byte) 0);
        }

        updateLocation();

        if (!isAlive()) {
            return;
        }

        regenerate();
        fallDamageCheck();

        setOldVelocity(getBotVelocity().copy());

        doTick();
    }

    private void regenerate() {
        float health = getHealth();
        float max = getMaxHealth();

        setHealth(health < max - REGEN_PER_TICK ? health + REGEN_PER_TICK : max);
    }

    private void updateLocation() {
        MotionVec velocity = getBotVelocity();
        double y = BotPhysics.step(velocity, getGroundTicks(), getJumpTicks(), isBotInWater());

        applyMotion(velocity.getX(), y, velocity.getZ());
    }

    public boolean isBotInWater() {
        // Matches the original exactly: probe feet, waist and head, and test the BLOCK
        // identity rather than the fluid state. Those differ — a waterlogged stair has a
        // non-empty FluidState but is not Material.WATER, so a getFluidState().isEmpty()
        // check would report true where the Paper build reported false.
        for (int i = 0; i <= 2; i++) {
            BlockPos pos = BlockPos.containing(getX(), getY() + (i * 0.9), getZ());
            Block block = level().getBlockState(pos).getBlock();

            if (block == Blocks.WATER || block == Blocks.LAVA) {
                return true;
            }
        }

        return false;
    }

    private boolean checkGround() {
        if (getBotVelocity().getY() > 0) {
            return false;
        }

        standingOn = GroundCheck.standingOn((ServerLevel) level(), getBoundingBox(), getBbHeight());
        return !standingOn.isEmpty();
    }

    public List<BlockPos> getStandingOn() {
        return standingOn;
    }

    /**
     * Blocks that cancel fall damage. Ported from {@code BotUtils.NO_FALL}; the Paper
     * build listed Materials, these are the equivalent Blocks.
     */
    private static final Set<Block> NO_FALL = Set.of(
            Blocks.WATER, Blocks.LAVA,
            Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
            Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
            Blocks.COBWEB, Blocks.VINE);

    private void fallDamageCheck() {
        if (getGroundTicks() == 0 || getNoFallTicks() != 0) {
            return;
        }

        double oldY = getOldVelocity().getY();
        if (oldY >= -0.8) {
            return;
        }

        if (isFallBlocked()) {
            return;
        }

        hurtServer((ServerLevel) level(), damageSources().fall(), (float) Math.pow(3.6, -oldY));
    }

    /**
     * True when the bot is landing in something that cancels fall damage — water, lava,
     * cobweb, powder snow, vines, sweet berries, or any waterlogged block.
     *
     * <p>Ported from {@code Bot.isFallBlocked}. The odd-looking {@code maxX - 0.01} and
     * {@code Math.floor} are upstream's; they are preserved.
     */
    private boolean isFallBlocked() {
        AABB box = getBoundingBox();
        double[] xs = {box.minX, box.maxX - 0.01};
        double[] zs = {box.minZ, box.maxZ - 0.01};

        AABB botBox = new AABB(box.minX, position().y - 0.01, box.minZ,
                box.maxX, position().y + getBbHeight(), box.maxZ);

        for (double x : xs) {
            for (double z : zs) {
                BlockPos pos = BlockPos.containing(Math.floor(x), getY(), Math.floor(z));
                BlockState state = level().getBlockState(pos);

                if (state.getValueOrElse(BlockStateProperties.WATERLOGGED, false)) {
                    return true;
                }

                Block block = state.getBlock();
                if (!NO_FALL.contains(block)) {
                    continue;
                }

                AABB blockBox = state.getCollisionShape(level(), pos).bounds().move(pos);
                if (botBox.intersects(blockBox) || block == Blocks.WATER || block == Blocks.LAVA) {
                    return true;
                }
            }
        }

        return false;
    }

    public void jump(MotionVec impulse) {
        if (getJumpTicks() == 0 && getGroundTicks() > 1) {
            setJumpTicks((byte) 4);
            getBotVelocity().setX(impulse.getX()).setY(impulse.getY()).setZ(impulse.getZ());
        }
    }

    public void jump() {
        jump(new MotionVec(0, 0.42, 0));
    }

    /** Adds to the bot's velocity, discarding non-finite input as the original did. */
    public void addVelocity(MotionVec delta) {
        if (BotMath.isNotFinite(delta)) {
            getBotVelocity().setX(delta.getX()).setY(delta.getY()).setZ(delta.getZ());
            return;
        }

        getBotVelocity().add(delta);
    }

    public void setRemoveOnDeath(boolean value) {
        this.removeOnDeath = value;
    }

    /**
     * Ported from {@code die} + {@code dieCheck}. The delay matters: the bot is
     * unregistered and hidden immediately, but the entity is not discarded for another
     * 20 ticks so the death animation can play out clientside.
     *
     * <p>Sending the despawn packets alone would leave the entity in the world forever.
     */
    @Override
    public void die(DamageSource cause) {
        super.die(cause);

        if (!removeOnDeath) {
            return;
        }

        TerminatorPlus.registry().remove(this);
        BotFactory.despawn(this);
        TerminatorPlus.registry().scheduler().runLater(20, this::removeBot);
    }

    /**
     * Knockback. Note: the Paper original computed both axes from getX()/getZ(), a
     * copy-paste bug. Ported verbatim per the spec's faithful-translation rule; see
     * spec section 5 step 3. Do not fix it here.
     */
    @Override
    public void push(Entity entity) {
        if (isPassengerOfSameVehicle(entity) || entity.noPhysics || this.noPhysics) {
            return;
        }

        double d0 = entity.getX() - this.getZ();
        double d1 = entity.getX() - this.getZ();
        double d2 = Mth.absMax(d0, d1);

        if (d2 < 0.009999999776482582D) {
            return;
        }

        d2 = Math.sqrt(d2);
        d0 /= d2;
        d1 /= d2;

        double scale = Math.min(1.0D / d2, 1.0D);
        d0 *= scale * 0.05000000074505806D;
        d1 *= scale * 0.05000000074505806D;

        if (!this.isVehicle()) {
            getBotVelocity().add(-d0, 0.0D, -d1);
        }

        if (!entity.isVehicle()) {
            entity.push(d0, 0.0D, d1);
        }
    }

    @Override
    public void doTick() {
        // detectEquipmentUpdatesPublic() was a Paper addition; vanilla's
        // detectEquipmentUpdates() is public as of 26.2.
        detectEquipmentUpdates();
        baseTick();
    }

```

- [ ] **Step 2: Verify it compiles**

```bash
cd /d/terminator-plus && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`. If `detectEquipmentUpdates()` is reported as not visible, confirm the
signature with:
`javap -p -cp build/…/minecraft.jar net.minecraft.world.entity.LivingEntity | grep detectEquipment`

- [ ] **Step 3: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/bot/Bot.java
git commit -m "feat: bot ticking, damage, death and removal

Wires BotPhysics into the entity tick and ports regen, fall damage, jumping and
knockback. hurt() became hurtServer(ServerLevel, ...) in 26.2, and
detectEquipmentUpdatesPublic() is now vanilla's public detectEquipmentUpdates().
push() keeps its upstream copy-paste bug per the faithful-translation rule.
Removal calls PlayerAdvancements.clearTriggers() to avoid NeoForge issue 1487.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 10: Brigadier commands

Replaces the Paper build's ~370-line reflection-and-annotation command framework.

Original: `git show paper-original:TerminatorPlus-Plugin/src/main/java/net/nuggetmc/tplus/command/commands/BotCommand.java`

**Files:**
- Create: `src/main/java/net/nuggetmc/tplus/command/BotCommands.java`

- [ ] **Step 1: Write the command tree**

`src/main/java/net/nuggetmc/tplus/command/BotCommands.java`:

```java
package net.nuggetmc.tplus.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotFactory;
import net.nuggetmc.tplus.bot.BotGameProfiles;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.util.MojangSkins;

import java.util.stream.Collectors;

/**
 * The {@code /tplus} command tree.
 *
 * <p>Gated at the gamemaster tier, the 26.2 equivalent of the old permission level 2,
 * matching the Paper build's {@code terminatorplus.manage} permission.
 *
 * <p>26.2 replaced {@code CommandSourceStack.hasPermission(int)} with a real permission
 * system: {@code Commands.hasPermission(PermissionCheck)} returns a
 * {@code PermissionProviderCheck}, which implements {@link java.util.function.Predicate}
 * and so can be handed straight to {@code requires}.
 */
public final class BotCommands {

    private static final int MAX_BOTS_PER_COMMAND = 100;

    private BotCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tplus")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> create(ctx, 1, false))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_BOTS_PER_COMMAND))
                                .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "count"), false))
                                .then(Commands.literal("playerlist")
                                        .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "count"), true))))));

        root.then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommands::removeOne)));

        root.then(Commands.literal("removeall").executes(BotCommands::removeAll));
        root.then(Commands.literal("list").executes(BotCommands::list));

        dispatcher.register(root);
    }

    private static int create(CommandContext<CommandSourceStack> ctx, int count, boolean playerList) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Fetching skin for " + name + "..."), false);

        MojangSkins.fetch(name).thenAccept(skin -> BotRegistry.onServerThread(server, () -> {
            for (int i = 0; i < count; i++) {
                String botName = count == 1 ? name : name + i;
                GameProfile profile = BotGameProfiles.create(botName, skin);

                Bot bot = BotFactory.spawn(level, pos, source.getRotation().y, source.getRotation().x,
                        profile, playerList);
                TerminatorPlus.registry().add(bot);
            }

            source.sendSuccess(() -> Component.literal(
                    "Spawned " + count + " bot(s)" + (playerList ? " in the player list" : "")), true);
        }));

        return count;
    }

    private static int removeOne(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Bot bot = TerminatorPlus.registry().byName(name);

        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("No bot named '" + name + "'"));
            return 0;
        }

        TerminatorPlus.registry().remove(bot);
        bot.removeBot();
        ctx.getSource().sendSuccess(() -> Component.literal("Removed bot '" + name + "'"), true);

        return 1;
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) {
        int removed = TerminatorPlus.registry().size();
        TerminatorPlus.registry().reset();

        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " bot(s)"), true);
        return removed;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var bots = TerminatorPlus.registry().bots();

        if (bots.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No bots are loaded."), false);
            return 0;
        }

        String names = bots.stream()
                .map(bot -> bot.getGameProfile().name())
                .sorted()
                .collect(Collectors.joining(", "));

        ctx.getSource().sendSuccess(
                () -> Component.literal(bots.size() + " bot(s): " + names), false);

        return bots.size();
    }
}
```

- [ ] **Step 2: Hook command registration into the mod class**

In `src/main/java/net/nuggetmc/tplus/TerminatorPlus.java`, add these two imports:

```java
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.nuggetmc.tplus.command.BotCommands;
```

Then replace the placeholder comment:

```java
    // The RegisterCommandsEvent handler is added in Task 10, once BotCommands exists.
```

with the handler:

```java
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BotCommands.register(event.getDispatcher());
    }
```

- [ ] **Step 3: Verify the commands work on a live server**

```bash
cd /d/terminator-plus && ./gradlew runServer
```

In the server console:

```
/tplus create Notch
/tplus list
/tplus remove Notch
/tplus list
```

Expected: `Spawned 1 bot(s)`, then `1 bot(s): Notch`, then `Removed bot 'Notch'`, then
`No bots are loaded.` Type `stop` when done.

- [ ] **Step 4: Commit**

```bash
cd /d/terminator-plus
git add src/main/java/net/nuggetmc/tplus/command/BotCommands.java src/main/java/net/nuggetmc/tplus/TerminatorPlus.java
git commit -m "feat: add Brigadier /tplus command tree

Replaces the ~370-line reflection-and-annotation command framework and the
SimpleCommandMap registration hack with Brigadier on RegisterCommandsEvent,
gated at permission level 2. Skin fetch stays off the server thread and
resumes via server.execute.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 11: Server-backed tests for both PlayerList paths

Attacks spec risk 1 — the least-understood part of the port — directly.

**Files:**
- Create: `src/test/java/net/nuggetmc/tplus/bot/BotSpawnTest.java`

- [ ] **Step 1: Write the test**

`src/test/java/net/nuggetmc/tplus/bot/BotSpawnTest.java`:

```java
package net.nuggetmc.tplus.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the NMS layer against a real headless server: the fake connection, the
 * two spawn paths, and cleanup on removal.
 *
 * <p>Spec risk 1: adding a bot to the real PlayerList makes the server treat it as an
 * online player, aiming playerdata saves and chunk dispatch at a connection that goes
 * nowhere. Both paths are tested so that risk surfaces here rather than in production.
 */
@ExtendWith(EphemeralTestServerProvider.class)
class BotSpawnTest {

    private static Bot spawnAt(MinecraftServer server, boolean playerList) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        assertNotNull(level, "overworld must exist");

        GameProfile profile = BotGameProfiles.create("TestBot", null);
        return BotFactory.spawn(level, new Vec3(0, 250, 0), 0f, 0f, profile, playerList);
    }

    @Test
    void spawnsAsAFreshEntityWithoutJoiningThePlayerList(MinecraftServer server) {
        Bot bot = spawnAt(server, false);

        assertTrue(bot.isAlive());
        assertFalse(bot.isInPlayerList());
        assertFalse(server.getPlayerList().getPlayers().contains(bot));
        assertSame(bot, server.getLevel(Level.OVERWORLD).getEntity(bot.getId()));

        bot.removeBot();
    }

    @Test
    void spawnsIntoThePlayerListWhenAsked(MinecraftServer server) {
        Bot bot = spawnAt(server, true);

        assertTrue(bot.isInPlayerList());
        assertTrue(server.getPlayerList().getPlayers().contains(bot));

        bot.removeBot();
        assertFalse(server.getPlayerList().getPlayers().contains(bot),
                "removeBot must take the bot back out of the player list");
    }

    @Test
    void tickingDoesNotThrowThroughTheFakeConnection(MinecraftServer server) {
        Bot bot = spawnAt(server, false);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 20; i++) {
                bot.tick();
            }
        }, "20 ticks must not throw through the fake connection");

        bot.removeBot();
    }

    @Test
    void tickingAPlayerListBotDoesNotThrow(MinecraftServer server) {
        // The riskier path: the server may try to send this bot packets.
        Bot bot = spawnAt(server, true);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 20; i++) {
                bot.tick();
            }
        });

        bot.removeBot();
    }

    @Test
    void anUnsupportedBotFallsUnderGravity(MinecraftServer server) {
        Bot bot = spawnAt(server, false);
        double startY = bot.getY();

        for (int i = 0; i < 10; i++) {
            bot.tick();
        }

        assertTrue(bot.getY() < startY, "bot should have fallen; y went " + startY + " -> " + bot.getY());

        bot.removeBot();
    }

    @Test
    void removalTakesTheBotOutOfTheWorld(MinecraftServer server) {
        Bot bot = spawnAt(server, false);
        int id = bot.getId();

        bot.removeBot();

        assertTrue(bot.isRemoved());
        assertNull(server.getLevel(Level.OVERWORLD).getEntity(id),
                "the entity must be gone from the level after removeBot");
    }

    @Test
    void registryEvictsRemovedBotsOnTick(MinecraftServer server) {
        BotRegistry registry = new BotRegistry();
        Bot bot = spawnAt(server, false);
        registry.add(bot);

        assertEquals(1, registry.size());

        bot.removeBot();
        registry.tick();

        assertEquals(0, registry.size(), "registry must drop bots removed from the world");
    }

    // --- In-world behaviour -------------------------------------------------
    // These need a loaded datapack because GroundCheck consults block tags, which
    // is exactly what a plain bootstrapped JUnit environment cannot provide.

    /**
     * Places a floor block and drops a bot onto it.
     *
     * <p>Y is deliberately high: the ephemeral server generates a normal overworld, and
     * a floor at ordinary terrain height could land inside a hill and wedge the bot,
     * making these tests flaky. Everything above y=200 is air outside extreme terrain,
     * and the column is cleared anyway.
     */
    private static Bot spawnAbove(MinecraftServer server, BlockPos floor, BlockState floorState, double height) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);

        // Clear the drop column so nothing generated interferes.
        for (int dy = 1; dy <= 50; dy++) {
            level.setBlockAndUpdate(floor.above(dy), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(floor, floorState);

        GameProfile profile = BotGameProfiles.create("GroundBot", null);
        Vec3 pos = new Vec3(floor.getX() + 0.5, floor.getY() + height, floor.getZ() + 0.5);

        return BotFactory.spawn(level, pos, 0f, 0f, profile, false);
    }

    @Test
    void botLandsOnASolidFloor(MinecraftServer server) {
        BlockPos floor = new BlockPos(64, 200, 64);
        Bot bot = spawnAbove(server, floor, Blocks.STONE.defaultBlockState(), 4);

        for (int i = 0; i < 60 && !bot.isBotOnGround(); i++) {
            bot.tick();
        }

        assertTrue(bot.isBotOnGround(), "bot never landed; y=" + bot.getY());
        assertFalse(bot.getStandingOn().isEmpty(), "standingOn was empty while on the ground");

        bot.removeBot();
    }

    @Test
    void botStandsOnAFence(MinecraftServer server) {
        // Exercises the BlockTags.FENCES branch of GroundCheck via typeHolder().is(...).
        BlockPos floor = new BlockPos(70, 200, 70);
        Bot bot = spawnAbove(server, floor, Blocks.OAK_FENCE.defaultBlockState(), 4);

        for (int i = 0; i < 60 && !bot.isBotOnGround(); i++) {
            bot.tick();
        }

        assertTrue(bot.isBotOnGround(), "bot did not come to rest on the fence; y=" + bot.getY());

        bot.removeBot();
    }

    @Test
    void botTakesFallDamageOnceTheGracePeriodHasElapsed(MinecraftServer server) {
        // noFallTicks starts at 60 and decrements once per tick, so a freshly spawned
        // bot is immune to fall damage for its first 60 ticks. Burn that off on solid
        // ground first, otherwise this test silently proves nothing.
        BlockPos floor = new BlockPos(76, 200, 76);
        Bot bot = spawnAbove(server, floor, Blocks.STONE.defaultBlockState(), 1);

        for (int i = 0; i < 70; i++) {
            bot.tick();
        }

        float healthBeforeDrop = bot.getHealth();
        bot.setPos(floor.getX() + 0.5, floor.getY() + 40, floor.getZ() + 0.5);

        for (int i = 0; i < 120 && bot.getHealth() >= healthBeforeDrop; i++) {
            bot.tick();
        }

        assertTrue(bot.getHealth() < healthBeforeDrop,
                "bot took no fall damage from 40 blocks: " + healthBeforeDrop + " -> " + bot.getHealth());

        bot.removeBot();
    }

    @Test
    void botsDoNotReportThemselvesAsFakePlayers(MinecraftServer server) {
        Bot bot = spawnAt(server, false);

        assertFalse(bot.isFakePlayer(), "spec risk 4: bots must look like real players to other mods");

        bot.removeBot();
    }
}
```

Add these imports to the test's import block:

```java
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
```

- [ ] **Step 2: Run the test**

```bash
cd /d/terminator-plus && ./gradlew test --tests '*BotSpawnTest*'
```

Expected: PASS, 11 tests. The first run boots a headless server and takes a minute or two.

**If the PlayerList tests fail**, that is spec risk 1 materialising and is valuable information, not
a blocker. Record the stack trace, then make `addToPlayerList` default to false and mark the failing
test `@Disabled` with the trace in the annotation reason. Report it in the task summary — do not
silently delete the test.

- [ ] **Step 3: Commit**

```bash
cd /d/terminator-plus
git add src/test/java/net/nuggetmc/tplus/bot/BotSpawnTest.java
git commit -m "test: cover both spawn paths against a real server

Uses EphemeralTestServerProvider to exercise the fake connection, both the
addFreshEntity and addNewPlayer paths, gravity, and removal cleanup. Targets
spec risk 1 directly, so PlayerList problems surface in CI rather than
in production.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 12: Record why GameTests are deferred

**No code in this task.** It exists so the deferral is a decision on the record rather than an
omission someone rediscovers later.

**Finding.** The spec's testing plan (section 6) assumed NeoForge's annotation-driven GameTests:
`@GameTestHolder(MOD_ID)` on a class and `@GameTest` on methods. **Those annotations do not exist in
Minecraft 26.2.** Verified against the 26.2 server jar: nothing named `GameTest.class`,
`GameTestHolder.class`, or `GameTestGenerator.class` remains, and NeoForge 26.2.0.87 ships no
`GameTestHolder` either.

Mojang replaced them with a datapack-driven system built on `GameTestInstance`:

```
net.minecraft.gametest.framework.GameTestInstance      (abstract, codec-backed)
net.minecraft.gametest.framework.GameTestInstances
net.minecraft.gametest.framework.GameTestEnvironments
net.minecraft.gametest.framework.TestEnvironmentDefinition
```

A test is now a registry entry: a `GameTestInstance` subclass with a `MapCodec`, registered into the
test-instance registry, plus datapack JSON declaring the instance, its environment, and its
structure. That is materially more infrastructure than annotations were.

**Decision.** Plan A does not build it, because the coverage GameTests were uniquely needed for is
already available one tier down. `EphemeralTestServerProvider` gives a real `MinecraftServer` with
**loaded datapacks**, so block-tag lookups resolve correctly there — which was the entire reason
spec section 6 pushed `BlockRules` and `GroundCheck` out of pure JUnit. Task 11 therefore covers
landing on stone, standing on a fence, fall damage, and `isFakePlayer`.

**Deferred to Plan B**, where the datapack test-instance infrastructure gets built once and serves
the much larger agent surface (pathfinding around obstacles, mining, combat) that genuinely needs
placed structures.

**Spec follow-up:** section 6 of the design doc still describes the annotation API and a
`gameTestServer` run config as Plan A's third tier. Update it to match this finding.

- [ ] **Step 1: Note the deferral in the spec**

Append to section 6 of `docs/superpowers/specs/2026-09-12-terminatorplus-neoforge-port-design.md`,
directly after the GameTests paragraph:

```markdown
> **Correction (2026-09-12, found during Plan A):** Minecraft 26.2 removed the `@GameTest` and
> `@GameTestHolder` annotations in favour of a datapack-driven `GameTestInstance` registry. The
> annotation-based tier described above does not exist. Plan A covers in-world behaviour with the
> server-backed JUnit tier instead, which has loaded datapacks and therefore working tag lookups.
> The `GameTestInstance` infrastructure is built in Plan B.
```

- [ ] **Step 2: Commit**

```bash
cd /d/terminator-plus
git add docs/superpowers/specs/2026-09-12-terminatorplus-neoforge-port-design.md
git commit -m "docs: record that 26.2 removed the GameTest annotations

@GameTest and @GameTestHolder no longer exist; Minecraft replaced them with a
datapack-driven GameTestInstance registry. Plan A covers in-world behaviour via
the server-backed JUnit tier, which has loaded datapacks and so working tag
lookups. The new infrastructure is deferred to Plan B.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---


## Definition of done

Plan A is complete when all of the following hold:

- [ ] `./gradlew build` succeeds with **44 tests** passing:

      | Suite | Tests | Tier |
      |---|---|---|
      | `MotionVecTest` | 8 | pure |
      | `BotMathTest` | 8 | pure |
      | `TickSchedulerTest` | 9 | pure |
      | `BotPhysicsTest` | 8 | pure |
      | `BotSpawnTest` | 11 | server-backed |

- [ ] On `./gradlew runServer`, `/tplus create Notch` spawns a visible, skinned bot
- [ ] The bot falls, lands, and takes fall damage from a height
- [ ] `/tplus list` shows it and `/tplus remove Notch` despawns it with no residue
- [ ] Stopping the server removes all bots without errors
- [ ] Spec risk 1 is resolved either way: both `PlayerList` paths pass, or the failure is recorded
      with a stack trace and `addToPlayerList` defaults to false

Then move to Plan B: the `LegacyAgent` translation.
