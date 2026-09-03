# Doorways

Articulated doors 1, 2, 3 and 4 blocks wide and 2 blocks tall, for Minecraft 26.2.
Runs on both Fabric and NeoForge.

The project started from a Portuguese specification document, kept outside this repository.
[DECISIONS.md](DECISIONS.md) amends it and is the source of truth ← **read this first**

## Status

**226 doors** across seven styles.

| Style | Materials | Widths | Doors |
|---|---|---|---|
| Solid | 21 | 1–4 | 84 |
| Glazed — glass in the upper half | 21 | 1–4 | 84 |
| Glass — glass throughout, iron frame | — | 1–4 | 4 |
| Saloon — spindles under an arch, on spring hinges | 12 woods | 2, 4 | 24 |
| Bookshelf | — | 1–4 | 4 |
| Fusuma — papered panels that slide | 12 woods | 2, 4 | 24 |
| Sliding glass | — | 2, 4 | 2 |

Saloon and sliding doors exist only at the even widths — one splits into two swinging leaves, the
other is built from leaves of two panels — and the wooden ones only in wood. Glass and bookshelf
doors have no material to vary.

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
unwaxed). Glass and bookshelf make 23 in all.

Each material uses its vanilla `BlockSetType`, and with it the correct opening and closing
sounds. Iron doors cannot be opened by hand, exactly like vanilla. Copper doors oxidise, take
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
blockstates to 28,096 — from six times the whole of vanilla down to roughly its equal, with 226
doors in it. The arithmetic, and why each property costs what it costs, is D-38.

That single fact is the source of nearly every bug this project has had, and it is worth
knowing before you touch anything: *a door that has moved is no longer where the world expects
it to be* — for reading redstone, for receiving neighbour updates, or for being cleaned up.
[DECISIONS.md](DECISIONS.md) has the full list, with causes.

## Generated assets

2734 files — 226 blockstates holding 13,248 variants between them, 832 block models, 645
textures, 325 recipes, 226 loot tables. None of it is hand-edited, and it comes from **two**
generators with a deliberate split:

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

It verifies that every door has a blockstate, loot table, item definition, model, texture and
translation, that every model a blockstate points at exists, that every texture a model asks for
exists, that every door belongs to exactly one tool tag, that no recipe produces something
unregistered, and that **no loot table names a property its door does not have**. It exits
non-zero on the first inconsistency.

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
fabric/          registration, creative tab, oxidation, datagen, GameTests
neoforge/        deferred registration, entrypoint, copper data map check
tools/           texture and asset generator (Python, no dependencies)
```

Since 26.1 Minecraft is **not obfuscated**, so there are no mappings and no remapping: both
loaders see exactly the same names. That is what makes `common` shareable for free.

`fabric` and `neoforge` include the **sources** of `core` and `common` directly rather than
depending on their jars. This avoids cross-project remapping, which is the brittle part of any
multi-loader setup.

## Requirements

- **JDK 25** — mandatory. Minecraft 26.2 ships Java 25 and mods must target it.
- Gradle 9.5.1, via the wrapper.

```bash
winget install EclipseAdoptium.Temurin.25.JDK
```

## Tests

Three layers, and it is worth understanding what each one can and cannot catch.

| Where | What | How to run |
|---|---|---|
| `core/src/test` | 20 JUnit tests | `gradlew :core:test` |
| `core/src/verify` | `GeometryCheck`, 2052 assertions, **zero dependencies** | `gradlew :core:geometryCheck` |
| `fabric` GameTests | 15 scenarios in a real world | `gradlew :fabric:runGameTest` |

**The pure-geometry assertions have never caught a single real bug.** That is not a criticism of
them — `DoorLayout` is a pure function of coordinates and was never wrong. Every bug lived at the
boundary with the world, which is why the GameTests exist and why each of the fifteen guards a
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

Gradle 8.9 on Java 17 fails here with `Unable to establish loopback connection`. Gradle 9.5.1 on
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

All verified against official sources, not guessed. See `gradle.properties` and D-01 in
[DECISIONS.md](DECISIONS.md).

| | |
|---|---|
| Minecraft | 26.2 |
| Java | 25 |
| Gradle | 9.5.1 |
| Fabric Loom | 1.17-SNAPSHOT (resolves to 1.17.20) |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.158.0+26.2 |
| ModDevGradle | 2.0.144 |
| NeoForge | 26.2.0.70 |

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

## License

MIT.
