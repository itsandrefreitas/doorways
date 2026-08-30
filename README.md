# Doorways

Articulated doors 1, 2, 3 and 4 blocks wide and 2 blocks tall, for Minecraft 26.2.
Runs on both Fabric and NeoForge.

- Original specification: `Doorways - Wide_Doors_Especificacao_v0.1.docx` (Portuguese)
- Decisions that amend it: [DECISIONS.md](DECISIONS.md) ← **read this first**

## Status

**200 doors** across five styles.

| Style | Materials | Widths | Doors |
|---|---|---|---|
| Solid | 21 | 1–4 | 84 |
| Glazed — glass in the upper half | 21 | 1–4 | 84 |
| Glass — glass throughout, iron frame | — | 1–4 | 4 |
| Saloon — spindles under an arch | 12 woods | 2, 4 | 24 |
| Bookshelf | — | 1–4 | 4 |

Saloon doors exist only at the even widths, where the door splits into two swinging leaves, and
only in wood. Glass and bookshelf doors have no material to vary.

| Module | What it is | Status |
|---|---|---|
| `core` | Pure geometry. Zero Minecraft. | ✅ 13 JUnit tests + 1016 assertions |
| `common` | Block, applied geometry, definitions | ✅ Placement, opening, redstone, oxidation |
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

A door is `width × 2` blocks, with no block entity. Every part reconstructs the whole from its
own `PART`, `FACING`, `HINGE` and `OPEN` state. Opening **moves blocks**: the leaf swings out of
its frame into the space beyond, and the frame is left empty.

That single fact is the source of nearly every bug this project has had, and it is worth
knowing before you touch anything: *a door that has moved is no longer where the world expects
it to be* — for reading redstone, for receiving neighbour updates, or for being cleaned up.
[DECISIONS.md](DECISIONS.md) has the full list, with causes.

## Generated assets

2023 files — 200 blockstates with 128 variants each, 565 textures, 289 recipes. None of it is
hand-edited, and it comes from **two** generators with a deliberate split:

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
translation, that every model a blockstate points at exists, and that no recipe produces
something unregistered. It exits non-zero on the first inconsistency.

Material colours are **sampled from the vanilla textures** (`tools/palettes.py` reads PNGs
straight out of the client jar), so each wood's tone matches the material it is named after.

## Layout

```
core/       pure Java — geometry, testable without the game
common/     vanilla API — WideDoorBlock, WeatheringWideDoorBlock, DoorVariant
fabric/     registration, creative tab, oxidation, datagen, GameTests
neoforge/   deferred registration, entrypoint, copper data map check
tools/      texture and asset generator (Python, no dependencies)
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
| `core/src/test` | 13 JUnit tests | `gradlew :core:test` |
| `core/src/verify` | `GeometryCheck`, 1016 assertions, **zero dependencies** | `gradlew :core:geometryCheck` |
| `fabric` GameTests | 5 scenarios in a real world | `gradlew :fabric:runGameTest` |

**The 1016 pure-geometry assertions never caught a single real bug.** That is not a criticism of
them — `DoorLayout` is a pure function of coordinates and was never wrong. Every bug lived at the
boundary with the world, which is why the GameTests exist and why each of the five guards a bug
that actually happened. See D-33.

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

- `PART` ranges 0..3 on every door, even width 1 (D-22)
- `POWERED` is deliberately absent from every blockstate JSON (D-24)
- opening and closing runs behind a thread-local transaction guard (D-29)
- copper conversions are detected in `onPlace`, not intercepted at the item (D-31)
- saloon doors are built from stacked boxes rather than one, so the arch has a silhouette (D-35)

## License

MIT.
