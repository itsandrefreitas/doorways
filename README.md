# Doorways

Articulated doors 1 to 4 blocks wide and 2 or 3 blocks tall, for Minecraft **26.2 and 26.3**.
Runs on both Fabric and NeoForge.

The project started from a Portuguese specification document, kept outside this repository.
[DECISIONS.md](DECISIONS.md) amends it and is the source of truth ← **read this first**

## Status

**234 doors** across eight styles.

| Style | Materials | Widths | Heights | Doors |
|---|---|---|---|---|
| Solid | 21 | 1–4 | 2 | 84 |
| Glazed — glass in the upper half | 21 | 1–4 | 2 | 84 |
| Glass — glass throughout, iron frame | — | 1–4 | 2 | 4 |
| Saloon — spindles under an arch, on spring hinges | 12 woods | 2, 4 | 2 | 24 |
| Bookshelf | — | 1–4 | 2 | 4 |
| Fusuma — papered panels that slide | 12 woods | 2, 4 | 2 | 24 |
| Sliding glass | — | 2, 4 | 2 | 2 |
| Stone — boards in a masonry doorway | 2 stones | 1, 2 | 2, 3 | 8 |

Saloon and sliding doors exist only at the even widths — one splits into two swinging leaves, the
other is built from leaves of two panels — and the wooden ones only in wood. Glass and bookshelf
doors have no material to vary. Stone stops at two columns, because a leaf of cut stone four
blocks across, turning on a hinge, is not a door anybody would believe.

**Saloon doors hang on double-acting spring hinges**, which is one mechanism and three
behaviours: they swing to either side, away from whoever pushes them; they return to their frame
on their own; and they ignore redstone, because a spring has no latch to be held open by. See
D-36.

**Sliding doors glide** rather than snapping, and they take **no cavity in the wall**: a leaf is
two panels on two tracks, and opening runs one behind the other, so the space a door occupies
open is the space it occupied shut. A 2-wide one leaves a doorway of 1 block, a 4-wide one of 2.
They need a **sliding track** to craft, and they are the reason this mod has a block entity and a
renderer at all. See D-37.

**Fusuma can be painted.** Nine paintings — pine, bamboo, cherry blossom, autumn maple, the great
wave, a waterfall, a mountain, the moon and koi — each crafted from paper and the thing it
depicts, put on with the painting in hand and taken off with a brush. A painting covers the
**whole door** and parts down the middle when it opens, so a 4-wide door is a wider picture
rather than the same one stretched. See D-39.

**Stone doors come three blocks tall as well as two**, in cobblestone and cobbled deepslate.
They are a leaf of weathered boards strapped in iron, hung in a doorway of masonry — the way
round a medieval door actually was — and the three-row ones carry a **round head**. They are also
the first doors with a height of their own, and height works the way width already did: a taller
door is a separate block, not a property of one door, which is what keeps five rows from
multiplying every door in the mod by five. A tall door is crafted from the short one of the same
width plus a course of stone per column. See D-40.

Every other door opens one way, stays where you left it, and answers a signal.

| Module | What it is | Status |
|---|---|---|
| `core` | Pure geometry. Zero Minecraft. | ✅ 20 JUnit tests + 2052 assertions |
| `common` | Blocks, applied geometry, definitions, the sliding renderer | ✅ Placement, opening, redstone, oxidation, sliding |
| `fabric` | Registration, creative tab, oxidation, datagen, GameTests | ✅ Client + dedicated server |
| `neoforge` | Deferred registration, tab, data maps | ✅ Client + dedicated server |

`common` contains **not a single import** from Fabric or NeoForge. What differs between the
loaders is listed in D-28 — and it is mostly a question of *when*, not *what*.

### Materials

12 woods — oak, spruce, birch, jungle, acacia, dark oak, mangrove, cherry, pale oak, bamboo,
crimson, warped — plus iron and **8 copper states** (four oxidation stages, each waxed and
unwaxed). Glass, bookshelf, cobblestone and cobbled deepslate make 25 in all.

Each material uses its vanilla `BlockSetType`, and with it the correct opening and closing
sounds. Iron doors cannot be opened by hand, exactly like vanilla; stone doors can, and open with
the heavy sound of one. Copper doors oxidise, take
wax from honeycomb, and are scraped back with an axe — as one door, not as loose columns.

## How a wide door works

A door is `width × 2` blocks. Every part reconstructs the whole from its own state — where it
sits along the wall, which way the door faces, which end it hinges on and where the leaf is — and
a swinging door needs no block entity to do it. A sliding one has a block entity for two things
only: where its panel is between the two positions its state can describe, and which painting is
on it. Opening **moves blocks**: the leaf swings out of
its frame into the space beyond, and the frame is left empty. A sliding door is the exception
that proves the rule: it moves nothing, and it has a block entity for drawing alone (D-37).

`SWING` replaced vanilla's boolean `open` because a spring-hinged door can be open on either
side, and a column has to know which — otherwise it cannot work out where its siblings are
(D-36).

**A door only declares the properties it reads.** A 1-wide door has no column index, a door that
opens from the middle has no hinge, a spring door records no signal, and only a sliding door
knows whether it is in flight. That is not tidiness: those four rules took the mod from 173,568
blockstates to 28,096 — from six times the whole of vanilla down to roughly its equal. It stands
at 28,736 today, with 234 doors in it, and that is the budget every new idea is measured against. The arithmetic, and why each property costs what it costs, is D-38.

That single fact is the source of nearly every bug this project has had, and it is worth
knowing before you touch anything: *a door that has moved is no longer where the world expects
it to be* — for reading redstone, for receiving neighbour updates, or for being cleaned up.
[DECISIONS.md](DECISIONS.md) has the full list, with causes.

## Generated assets

3024 files — 234 blockstates holding 13,568 variants between them, 840 block models, 653
textures, 333 recipes, and 234 loot tables **per game version**. None of it is hand-edited, and
it comes from **two** generators with a deliberate split:

| Generator | Owns | Why |
|---|---|---|
| `gradlew :fabric:runDatagen` | blockstates, item definitions | anything derived from **geometry** |
| `python tools/gen_assets.py .` | textures, models, recipes, loot tables, lang | anything that is **pixels or plain data** |

The split is not arbitrary. Blockstates decide which way a leaf faces, so they must come from
the same `DoorLayout` the game runs — otherwise a door can *behave* one way and *look* another,
and nothing would fail. Everything else is data repetition, and textures can never come from
datagen at all: it emits JSON, not PNG. See D-34.

Because the two generators agree on names only by both following the same style table, a third
script checks that they still do:

```bash
python tools/check_assets.py .
```

It verifies that every door has a blockstate, item definition, model, texture and translation,
that every model a blockstate points at exists **and every model is pointed at**, that every
texture a model asks for exists, that every door belongs to exactly one tool tag, that no recipe
produces something unregistered, that **no loot table names a property its door does not have**,
and — for **every supported game version** — that each door has a loot table there and that it
is written in that version's own format. It exits non-zero on the first inconsistency.

That last pair is the argument for keeping both versions in one branch rather than two. A loot
table in the wrong format is refused by the game and the door drops nothing, silently; on
separate branches this script could only ever check the branch it was standing on.

The second half of that model rule is what catches a **stale blockstate**: the two generators can
be run apart, so adding a row kind writes models no variant mentions while every other check
stays green and the doors in game quietly keep their old heads. Adding it also turned up 48
models that had been shipping in every release for nothing — a saloon door exists only at the
even widths, so the one-column role it was generating could never occur.

That last rule was written the day it was needed. When the column index became one property per
width, the 44 one-column doors kept a condition naming the property they had just lost; the whole
table then failed to parse, and those doors silently dropped nothing at all. Every other check
passed, because everything else about them was right.

The texture half reads the reference out of the model rather than comparing directory listings
by name. Matching names was the simpler rule and it was wrong: a door has two models per leaf --
in its frame and swung out of it -- sharing one texture, and a rule forbidding that polices a
naming convention instead of the thing that actually breaks.

Material colours are **sampled from the vanilla textures** (`tools/palettes.py` reads PNGs
straight out of the client jar), so each wood's tone matches the material it is named after.

### The paintings are drawn by code, not stored as pictures

The nine fusuma paintings are functions, built from a handful of marks — a stroke that tapers, a
fan of short strokes, a mass with a broken edge, a pale silhouette for distance, the moon as
unpainted paper, the painter's seal. That is what lets a motif **recompose itself** for a wider
door instead of being stretched across it, and what makes a tenth painting a function rather than
a file.

It also means the failures are readable. Each rejected attempt is a comment where it was tried,
with the reason: mist that read as dirt, bark ticks that read as loose pixels, diagonal fills
that read as machine hatching, a branch long enough to be a pole, and a crane that could not be
drawn at this size and became koi. See D-39.

## Layout

```
core/            pure Java — geometry, testable without the game
common/block/    WideDoorBlock and the three subclasses, DoorVariant, DoorStyle
common/client/   the sliding renderer — the only client code outside the loaders
common/test/     the GameTest scenarios, in vanilla API so both loaders could run them
common/src/main/java-<version>/       Vanilla.java — the spellings that differ, and nothing else
common/src/main/resources-<version>/  the loot tables, whose format changed in 26.3
fabric/          registration, creative tab, oxidation, datagen, GameTests
neoforge/        deferred registration, entrypoint, copper data map check
tools/           texture and asset generator (Python, no dependencies)
versions/        one file per Minecraft version: what differs between them
```

Since 26.1 Minecraft is **not obfuscated**, so there are no mappings and no remapping: both
loaders see exactly the same names. That is what makes `common` shareable for free.

`fabric` and `neoforge` include the **sources** of `core` and `common` directly rather than
depending on their jars. This avoids cross-project remapping, which is the brittle part of any
multi-loader setup.

## Two Minecraft versions, one branch

```bash
gradlew build                # the newest version — 26.3
gradlew build "-Pmc=26.2"
gradlew minecraftVersions    # what this tree can build, and which is the default
```

**Quote the flag on PowerShell.** It splits an unquoted `-Pmc=26.2` into two arguments,
`-Pmc=26` and `.2`, so the build is handed a version that does not exist. The error says so when
it happens, but the quotes avoid it.

The versions are listed in `minecraft_targets` in `gradle.properties` and described one file
each in `versions/`. Nothing else in the build holds a version number.

Almost the whole mod is written once. What differs is:

| | |
|---|---|
| `versions/<version>.properties` | the version numbers of Minecraft, Fabric API and NeoForge |
| `common/src/main/java-<version>/…/Vanilla.java` | the two expressions vanilla renamed |
| `common/src/main/resources-<version>/` | the loot tables — 26.3 rebuilt loot conditions |

**`Vanilla.java` is the measure of the drift.** It works while every difference is one
expression that can be given a name. The first one that cannot — a method that has to stop
overriding something, a class that has to implement a different interface — is the signal to
move to a preprocessor. See D-41.

Switching versions makes Gradle fetch that Minecraft and re-run datagen, so the first build
after a switch is slow. `build/` and `run/` belong to whichever was built last.

## Requirements

- **JDK 25** — mandatory. Minecraft 26.2 and 26.3 both ship Java 25 and mods must target it.
- Gradle 9.8.0, via the wrapper. 26.3 needs 9.6 or newer.

```bash
winget install EclipseAdoptium.Temurin.25.JDK
```

## Tests

Three layers, and it is worth understanding what each one can and cannot catch.

| Where | What | How to run |
|---|---|---|
| `core/src/test` | 20 JUnit tests | `gradlew :core:test` |
| `core/src/verify` | `GeometryCheck`, 2052 assertions, **zero dependencies** | `gradlew :core:geometryCheck` |
| `fabric` GameTests | 16 scenarios in a real world | `gradlew :fabric:runGameTest` |

**The pure-geometry assertions have never caught a single real bug.** That is not a criticism of
them — `DoorLayout` is a pure function of coordinates and was never wrong. Every bug lived at the
boundary with the world, which is why the GameTests exist and why each of the sixteen guards a
bug that actually happened. See D-33.

Several were written after the fact: the two-way saloon door shipped with defects that no
assertion caught and that were found by standing in front of the door and looking at it (D-36),
the drop test was written the day 44 doors were found dropping nothing, and the painting test the
day opening a door was found to wipe the painting off it.

`GeometryCheck` lives in its own source set on purpose: it is a program with `main()`, not a
JUnit test. It runs with nothing but a JDK — no Gradle, no Minecraft:

```bash
javac -encoding UTF-8 -d build/classes $(find core/src/main core/src/verify -name '*.java') && java -cp build/classes com.doorways.core.geometry.GeometryCheck
```

GameTests need the Mojang EULA accepted, which is a decision for whoever runs them: set
`eula = true` inside `fabricApi.configureTests` in [fabric/build.gradle](fabric/build.gradle).

## Running the game

```bash
gradlew :fabric:runClient
gradlew :neoforge:runClient
gradlew :fabric:runServer
gradlew :neoforge:runServer
```

Dedicated servers need `eula=true` in their `run/eula.txt`, generated on the first attempt.

## Environment notes

Gradle 8.9 on Java 17 fails here with `Unable to establish loopback connection`. Gradle 9.x on
JDK 25 does not — it is not a firewall issue, it is the old combination. Always use the wrapper.

The warning `WARNING: A restricted method in java.lang.System has been called` comes from
Gradle's own `native-platform` on Java 25 and is harmless.

`:neoforge:runClient` stutters badly here — `Can't keep up! Running N ticks behind` — and doors
that slide or swing shut look broken because of it. It is the development launcher, not the mod:
the same build installed in a real NeoForge profile behaves exactly like the Fabric one. Test
NeoForge changes in an installed profile before believing a defect that only appears in
`runClient`.

Do not add the `foojay-resolver-convention` plugin. It references a `JvmVendorSpec` field
removed in Gradle 9.5 and breaks every task that requests a toolchain. See D-30.

## Versions

All verified against official sources, not guessed: the Fabric meta API for the loader,
Modrinth for Fabric API, the NeoForged maven for NeoForge, and Mojang's own version manifest
for the Java release. See `gradle.properties`, `versions/`, and D-01 in
[DECISIONS.md](DECISIONS.md).

Shared by both:

| | |
|---|---|
| Java | 25 |
| Gradle | 9.8.0 |
| Fabric Loom | 1.17-SNAPSHOT (resolves to 1.17.20) |
| ModDevGradle | 2.0.147 |

Per game version:

| | 26.2 | 26.3 |
|---|---|---|
| Fabric Loader | 0.19.3 | 0.19.5 |
| Fabric API | 0.158.0+26.2 | 0.161.0+26.3 |
| NeoForge | 26.2.0.70 | 26.3.0.31-beta |

NeoForge has published nothing but betas for 26.3, so that is also the only build a player on
26.3 can install.

## Contributing

Read [DECISIONS.md](DECISIONS.md) first. It is not a changelog — it records *why* each
non-obvious choice was made, including the ones that were wrong first. Several things in this
codebase look odd until you know the reason:

- a door is built through `WideDoorBlock.sized(width, mode, ...)` and throws if it is not, because
  the state definition needs both before the constructor can hold either (D-38)
- `POWERED` is deliberately absent from every blockstate JSON (D-24)
- opening and closing runs behind a thread-local transaction guard (D-29)
- copper conversions are detected in `onPlace`, not intercepted at the item (D-31)
- saloon doors are built from stacked boxes rather than one, so the arch has a silhouette (D-35)
- every leaf has two models, in its frame and swung out of it, differing only in mirrored UVs (D-36)
- a leaf's rotation comes from its pivot, never from the direction it swung (D-36)
- a sliding door's block entity holds nothing the game needs — remove it and doors snap instead of
  gliding, and nothing else changes (D-37)
- the sliding glass door is drawn by its renderer even standing still, and no other door is (D-37)
- a painting is not a blockstate property, and the reason is 82,944 blockstates (D-39)
- opening a door only demolishes it when the door actually changes place, and the day that was
  merely wasteful is the day it deleted paintings (D-39)
- the vertical index is spelled two ways — vanilla's `half` at two rows, a plain `row` above —
  and the reason is every door standing in every world that already exists (D-40)
- growing a door taller did not touch the geometry module by a single line, because a door's
  geometry is horizontal and always was (D-40)

## License

MIT.
