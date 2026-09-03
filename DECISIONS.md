# Doorways — Decision Log (amendment to Specification v0.1)

The project started from a Portuguese specification document, kept outside this repository.
This document records the **explicit later changes** that the spec itself anticipates, settles
the decisions it left open, and is the source of truth wherever the two disagree.

Status: **v0.5** · Last updated: 2026-09-03

This is not a changelog. It records *why* each non-obvious choice was made — including the ones
that were wrong first. If you are here to contribute, this file is worth more than the code.

---

## D-01 — Target environment (fills the §1 gap)

| Item | Value |
|---|---|
| Minecraft | **26.2** (Java Edition, *Chaos Cubed*, 2026-06-16) |
| Java | **25** (minimum required by 26.2) |
| Loaders | **Fabric** and **NeoForge** |
| Mappings | **None** — see below |
| Fabric Loom | `net.fabricmc.fabric-loom` 1.17-SNAPSHOT (resolves to 1.17.20) · Gradle 9.5.1 |
| Fabric Loader | 0.19.3 · Fabric API 0.158.0+26.2 |
| NeoForge | 26.2.0.70 · ModDevGradle 2.0.144 |

§1 said only "Fabric". This becomes **multi-loader**.

### Minecraft is no longer obfuscated

26.1 was the first non-obfuscated release, and 26.2 keeps it that way. Consequences:

- **There are no mappings.** `loom.officialMojangMappings()` is rejected with
  *"Cannot use Mojang mappings in a non-obfuscated environment"*. The line simply does not exist
  in the build.
- The correct plugin is **`net.fabricmc.fabric-loom`**. `net.fabricmc.fabric-loom-remap` is for
  1.21.11 and earlier.
- **Yarn was discontinued** by the Fabric project from here on.
- This removes the main source of multi-loader friction: both loaders see exactly the same
  names, and the `common` module is shareable with no translation whatsoever.
- No mod compiled for 1.21.11 or earlier works without recompilation.

### Porting note for 26.2

26.2 separated block and item ids from the objects themselves (`BlockIds`, `BlockItemIds`,
`ItemIds`) and removed `valueLookupBuilder`. The `ModBlocks` / `ModItems` design in §9 has to
reflect that. `LootData` was removed — relevant to §7.

**Every API signature is confirmed against the project's decompiled sources before use, per
§10.** Nothing in this document should be treated as a literal signature.

---

## D-02 — Material scope in v0.1 (fills the §8 gap)

> **Widened on 2026-08-29.** It started as "oak only" and the bet paid off: adding the remaining
> materials was one iteration, with no refactoring of the logic.

**21 materials, 168 doors** at the time of this decision. The 12 vanilla woods plus bamboo,
iron, and the **8 copper states** (four oxidation stages × waxed/unwaxed). Glass and bookshelf
arrived later with their own styles, bringing it to 23 materials and 200 doors -- see D-35.
The sliding styles brought it to **226** without adding a material (D-37).

Each material brings its own vanilla `BlockSetType`, and with it the correct opening, closing
and step sounds, plus `canOpenByHand` — the iron door refuses to open by hand without a single
line of our own code. Crimson and warped do not burn; iron and copper have strength 5 instead
of 3.

**The colours are not chosen: they are sampled.** `tools/palettes.py` reads the vanilla textures
out of the client jar and extracts four tones by luminance percentile. Oak was being drawn with
an invented `108,76,45` when the real `oak_planks` is `175,143,85` — almost 40% darker, and it
was noticed in game. None of the 21 tones is my choice any more.

---

## D-03 — `HINGE` becomes part of the state (corrects §3)

§3 lists `FACING`, `OPEN`, `HALF`, `PART`, but §2.2 refers to a "hinge" that does not exist in
the state model. Added:

```
HINGE = LEFT | RIGHT
```

Per-part state in v0.1:

| Property | Values | Notes |
|---|---|---|
| `FACING` | N / S / E / W | orientation of the closed door |
| `OPEN`   | true / false | |
| `HALF`   | LOWER / UPPER | |
| `PART`   | `0 .. width-1` | column index; one property per width since D-38 |
| `HINGE`  | LEFT / RIGHT | **no effect when `MODE = SPLIT`** (see D-05) -- and, since D-38, not declared there at all |

Properties of the block **class**, not of the BlockState (§3's decision stands):
`WIDTH` ∈ {1,2,3,4} and `MODE` ∈ {SINGLE, SPLIT}.

~~`POWERED` stays out of v0.1.~~ **Revoked** — see D-24.

---

## D-04 — Which way the leaf opens (fills the critical gap: §1–§6 never decides this)

**The leaf always swings towards `+FACING`** — away from whoever placed the door.

Since `FACING` is the direction the player is looking when placing, `+FACING` points into the
opening. Deterministic, and needs no new state.

> **Fixed on 2026-08-29.** It was implemented as `−FACING`, contradicting this very text: the
> leaf retreated towards whoever placed it. It only showed at widths 3 and 4 — 1 and 2 do not
> translate, the leaf rotates within its own block and the wrong sign never surfaced. Found in
> game, not by the tests: the assertions had been written from the same misconception, and so
> confirmed it.

The wall side is fixed; the **hinge** is what gives the player a choice, mirroring the leaf
between the two ends (see D-23). A `SWING` property allowing the door to open the other way
stays out of scope — vanilla does not allow it either.

---

## D-05 — `MODE` is a real property with a per-width default (corrects §2)

§2 pins the mode to the width, which made §3's `MODE` decorative.

```
MODE.defaultFor(width) = SPLIT if width is even, SINGLE if odd
  1 → SINGLE    2 → SPLIT    3 → SINGLE    4 → SPLIT
```

The values in §2's table stand **as v0.1 defaults**, but `MODE` is implemented as a real
property so that v0.2 can add variants (e.g. a 2-wide door that swings entirely to one side)
without refactoring the geometry.

Consequence: when `MODE = SPLIT`, each half has its own implicit hinge at the ends, so
**`HINGE` is ignored**.

> This sat here as a footnote for a month. It was worth 15,232 blockstates: a property ignored is
> a property that should not be declared, and D-38 finally stopped declaring it.

---

## D-06 — Space validation covers only the *new* positions (fixes a §5/§6 bug)

§6 says "every position required by the destination state must be free". But some destination
positions are already occupied by the door itself (the hinge column is shared between open and
closed). As written, a 3-wide door could never open — it would block itself.

Corrected rule:

```
positions_to_validate = destination \ current
```

---

## D-07 — Consequence of D-04: widths 1 and 2 never move blocks

From the geometry (see `core`), with the hinge at the ends:

| Width | Mode | Columns that change position on opening |
|---|---|---|
| 1 | SINGLE | **none** |
| 2 | SPLIT | **none** |
| 3 | SINGLE | 2 columns |
| 4 | SPLIT | 2 columns (1 per leaf) |

For widths 1 and 2 the leaf rotates within its own block, exactly like a vanilla door — only the
model and the `VoxelShape` change. **Only widths 3 and 4 move blocks.** This confirms §6 and cuts
the risk surface considerably.

---

## D-08 — Atomic transaction (makes §7 implementable)

§7 wants breaking any part to destroy the whole structure, and §5 wants to move blocks. During a
toggle the structure is temporarily inconsistent, and every `setBlock` fires neighbour updates
that would trigger the "broken structure" logic mid-swing.

Required:

1. A **transaction flag**: while active, parts ignore neighbour updates and the self-destruct
   logic.
2. `setBlock` with flags that **suppress drops** and defer client updates.
3. A **single flush** of neighbour updates at the end of the transaction.
4. The transaction is server-side. It never runs under `level.isClientSide`.

Without this, §11's "Breaking" and "Robustness" criteria do not pass.

---

## D-09 — Single drop (implements §7)

Every part has an **empty loot table**. The breaking path detects the structure and explicitly
calls `dropResources` **once**, with the door item. No drop in creative mode.

---

## D-10 — Ground support (settles the open point in §4.4)

- Support required **only on the closed footprint** and **only at placement**: each lower column
  needs a solid upward face beneath it.
- Support is **not** revalidated on opening. Opening a door over a gap does not fail.
- If the support under any closed column disappears, the whole structure is removed
  (via D-08 / D-09).

---

## D-11 — Entities do not block (fills the §6 omission)

In v0.1, **only blocks** prevent opening or closing. Players, mobs, items and vehicles in the
destination position are ignored and simply pushed by the new collision.

Door parts also **never suffocate** entities — unlike a normal solid block, and unlike what
would happen by default to a block moving into a player.

---

## D-12 — Fluids and replaceable blocks (fills the §4.3 omission)

- The door is **not waterloggable**, like the vanilla door.
- Placement is **refused** if any position contains fluid.
- Only blocks the game marks as replaceable count as occupiable (air, tall grass, snow layers
  and similar).
- **The same rule applies when opening**, not only when placing. Testing just `isAir()` on
  opening made any 3- or 4-wide door placed in grass impossible to open: placement accepted the
  terrain and opening refused it. Found in game.

---

## D-13 — Blocked sound (implements §5.1)

There is no vanilla "door blocked" event. We use the **locked container** sound, which is the
canonical vanilla "you can't" feedback and satisfies §5.1 without inventing audio.

One missing requirement is added: a **per-door cooldown**, so that repeatedly clicking a blocked
door does not machine-gun the sound.

The normal open/close sound does **not** play when the operation is cancelled.

---

## D-20 — Recipes (fills the §8 gap)

Planks + iron, with no dependency on vanilla doors. The number of iron ingots sets the width.

| Width | 3×3 grid | Cost |
|---|---|---|
| 1 | `PPI / PP. / PP.` | 6 planks + 1 iron |
| 2 | `PPI / PPI / PP.` | 6 planks + 2 iron |
| 3 | `PPI / PPI / PPI` | 6 planks + 3 iron |
| 4 | 2 × Doorway 2 + 1 iron | — |

Width 4 does not fit in the grid with a fourth iron. Making it from two 2-wide doors **mirrors
the mechanism**: §2.3 defines the 4-wide door as two rigid 2-wide leaves.

Glazed: **solid door + 1 glass**, shapeless, one per width.

None of these shapes collides with the vanilla door recipe (`PP / PP / PP`, no iron).

---

## D-21 — Glazed variants are separate blocks, not a property

Upper half in glass, at all 4 widths. **4 widths × 2 styles = 8 blocks.**

They cannot be a blockstate property: in Minecraft the render layer is defined **per block**,
not per state. A `GLAZED` property on the same block could not be opaque in one state and
translucent in another.

Consequences:

- Render layer registration is **loader-specific** — the first thing that does not fit in
  `common`.
- Glazed doors do not occlude light and need `noOcclusion`.
- Only the **upper half** textures differ between the two styles.

---

## D-22 — `PART` is 0..3 on every block, even the narrow ones

> **SUPERSEDED on 2026-09-02 by D-38.** The reasoning below is still why this is hard; the
> conclusion cost 22,528 unreachable states and was reversed. `PART` is now one property per
> width, and a door with one column has none at all.

`createBlockStateDefinition` is called from `Block`'s constructor, before subclass fields are
assigned. There is no way to declare a `PART` range that depends on the instance's width without
fragile tricks.

We accept `PART = 0..3` for all, ignoring values above `width-1`. Cost: unused states at widths
1, 2 and 3. This revokes the objection raised in the initial §3 analysis — the alternative would
be one class per width, which §10 explicitly forbids.

Total: 4 × 2 × 2 × 2 × 4 = **128 states per block**, 1024 across the set. This makes D-15's
datagen mandatory, not optional.

---

## D-14 — Appearance: consciously accepted (corrects the §8 expectation)

> **REVOKED on 2026-08-28.** v0.1 now has its own textures — see below.

Vanilla door textures have the hinge and frame drawn into the texture itself. Repeated across 3
or 4 columns, the result reads as **N doors glued together**, not as a single leaf.

The original decision was to accept that in v0.1. **It was reverted on explicit request:** the
doors must look distinct from vanilla. This also reverts the corresponding clause in §12 ("new
textures out of scope").

The reversal solves the problem instead of deferring it — widths 3 and 4 can now have a visually
continuous leaf, with frame and hinge only at the ends.

Accepted cost: textures become mandatory v0.1 work. Each column needs an upper and lower half,
and columns divide into hinge-end, middle and free-end.

The first set is **generated by code** — coherent and clearly non-vanilla, but not an artist's
work. It is a replaceable base, not a final result.

---

## D-15 — Asset generation (corrects §11/§14)

`FACING`(4) × `OPEN`(2) × `HALF`(2) × `HINGE`(2) × `PART`(≤4) ⇒ **up to 128 blockstate variants
per block**. Writing that by hand is impractical.

> **Now 192.** `OPEN` became the three-valued `SWING` in D-36. The count matters less than the
> shape: five properties is exactly what `PropertyDispatch` holds, which is why the third state
> had to replace the boolean rather than join it.

An explicit **datagen** step enters the development sequence, before polish — not as the last
clause of §14. See D-34 for what actually ended up in datagen, and why not everything did.

---

## D-16 — Automated tests (reinforces §9, §11 and §15)

- **`core` (pure Java, zero Minecraft)** — geometry tested with plain assertions. Runs without
  the game and without Minecraft's Gradle.
- **GameTest** — behaviour in the world: placement, blocking, single drop, persistence.

This stops §15 from being a manual checklist. Delivered — see D-33.

---

## D-17 — Naming (fixes the §9 inconsistency)

`modid = doorways`, name `Doorways`. The main class is called `Doorways`, not `WideDoorsMod`.
The blocks keep the `WideDoor*` name.

---

## D-18 — Diagrams

The ASCII diagrams in §2.1, §2.2, §2.3 and §6 are illegible or corrupted in the document (the
"OPEN" one in §6 is identical to the "CLOSED" one). They are replaced, as the geometric source
of truth, by the **offset tables** produced and verified by the `core` module.

---

## D-26 — Copper: a door is one object, not a cluster of columns

Oxidation, waxing, unwaxing and scraping act on the **whole structure**. A 4-wide door oxidises
as one door, not as eight independent blocks.

The opposite was considered — each column oxidising on its own, giving a mottled effect.
**Rejected:** §3's "one logical door" model assumes every part is the same block, and `clear`,
`apply`, `removeRest` and `isFree` all call `is(this)`. Columns of different types break D-08's
atomic transaction and bring orphaned parts back.

Three things were needed to make it work:

1. **`changeOverTime` overridden.** Vanilla's probability is untouched; only what it applies
   changes — `convertStructure` swaps all `width × 2` parts at once. Vanilla swapped only the
   ticked position, leaving the door with one column a different colour.

2. **`getNextState` overridden.** Vanilla counts oxidisable blocks within radius 4 and slows down
   the more there are of the same age. A 4-wide door counted its own 7 parts: `chance = 1/8`,
   squared, **16× slower** than a vanilla door. The door's own columns no longer count.

3. **Axe and honeycomb handling.** Originally intercepted in `useItemOn`; that turned out to be
   wrong twice over — see D-31 for what replaced it and why.

Only the `PART 0` column ticks the clock — one door, one clock, whatever the width.

Vanilla synchronises a door's two halves through `updateShape`, where the upper half copies the
block type from the lower. We handle both explicitly and do not rely on that.

---

## D-30 — No `foojay-resolver-convention`

The plugin `org.gradle.toolchains.foojay-resolver-convention:0.8.0` references
`JvmVendorSpec.IBM_SEMERU`, a field removed in Gradle 9.5. Any task requesting a toolchain blew
up with:

```
Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field '… IBM_SEMERU'
```

Only NeoForge triggered it, because only its run tasks request a toolchain explicitly. Fabric
sailed through — which is why the problem only appeared when the second loader was wired up,
long after the plugin had been added.

The plugin exists to **download JDKs automatically**. It is not needed: JDK 25 is installed and
the daemon itself runs on it. Removed.

> **The initial diagnosis was wrong.** I attributed this to an incompatibility between
> ModDevGradle and Gradle 9.5, and went as far as downgrading the wrapper to 9.2.1 — which broke
> Loom, since it requires 9.5.0+ (`org.gradle.plugin.api-version`). The field was never in MDG.
> It was in a plugin I had added myself, on plausibility, when setting the project up, and which
> was never verified against anything.

Gradle stays on **9.5.1**, as Fabric recommends.

---

## D-37 — Doors that slide, and the renderer they needed

A sliding door was asked for as a style of its own: Japanese in shape, opened by hand or by
redstone, and — the constraint that decided everything else — **without a cavity in the wall for
the leaf to disappear into**. A cavity ruins the build it is cut into, and a door that damages
the wall around it is not a door anyone will use.

### A leaf is two panels, one behind the other

That constraint rules out the obvious implementation. Without somewhere to hide, a leaf can only
slide as far as its own width, so a leaf is made of **two panels running on two tracks**: shut,
they sit side by side and fill two blocks; open, one runs behind the other and both occupy one.

The consequence is worth stating plainly, because it is what makes a sliding door different from
every other door here: **it displaces nothing.** The columns it occupies open are the columns it
occupied shut. `DoorLayout` says so directly — under `Motion.SLIDE` the offsets collapse to the
closed ones, and what changes is `panelsAt`, which puts the whole leaf in the column it parks in
and leaves the other empty.

| Width | Mode | Open leaves | Doorway |
|---|---|---|---|
| 2 | SINGLE | one leaf, slides to one side | 1 block |
| 4 | SPLIT | two leaves, parting from the middle | 2 blocks |

Odd widths are not offered: a leaf is two panels, so a door is a whole number of leaves.

### `Motion` is not `DoorMode`

Sliding is a **motion**, and it is deliberately a separate enum from the mode. `DoorMode` says how
many leaves a door has and where they part; `Motion` says what a leaf does when it opens. Keeping
them apart is what lets a 4-wide sliding door be `SPLIT` and slide, rather than needing a third
mode that means "split, but sliding" — and it is what leaves room for a gate that slides in two
halves without touching either enum.

### The drawing had to leave the blockstate behind

A blockstate is a discrete thing. It can say *shut* and it can say *open*, and it has nowhere to
put a panel that is a third of the way across. Every door before this one snapped between two
positions and looked right doing it, because a hinge turns and a snap is a turn you missed. A
panel that slides has to be **somewhere in between**, or it is not sliding at all.

So the mod gained its first block entity and its first client code — and neither of them holds
anything the game depends on. `SlidingPanelsBlockEntity` stores no state, syncs no packets and
writes nothing to disk: it watches its own blockstate, notices when the answer changes, and
interpolates. A door whose block entity vanished would still open, still close and still drop
correctly; it would simply stop gliding and start snapping, like every other door in the mod.

**One door, one departure.** Each panel keeps its own start, finish and clock, but takes the
*moment of departure* from the door's anchor: the first panel to notice sets it, the rest are
handed it. Sharing the running clock instead was tried and was worse — a panel that had not yet
noticed went on interpolating its previous journey against a stopwatch that had just been reset,
and slid backwards for a tick. The two roles are separate fields on the anchor for that reason,
and mixing them back into one is exactly the bug it looks like it cannot be.

### Why the renderer does not simply draw every sliding door

It did, at first, and that made them **disappear past 64 blocks** — the default reach of a block
entity renderer. The fix was to hand the drawing back and forth: `SlidingDoorBlock.SLIDING` marks
the third of a second a panel is travelling, the block goes invisible for exactly that long, and
the rest of the time it is an ordinary block batched into the chunk mesh like any other. The
renderer's reach was also raised to 512, because a door that opens 100 blocks away should still
be seen opening.

That handover is invisible only while both sides draw the same pixels in the same place. Three
times it did not, and each one was a different lesson:

- **A chunk's mesh is rebuilt one to three frames late.** On arrival the flag cleared, the
  renderer let go in the same frame, and the mesh that was to replace it was still the old one —
  which drew nothing. The door blinked out at the end of every slide. Fixed by three ticks of
  deliberate overlap.
- **A stale scheduled tick can end the wrong journey.** Every departure schedules the tick that
  ends it; a door told to reverse departs again and leaves the first tick in the queue, due in
  the middle of the second journey. It cleared the flag early, the panel was dropped wherever it
  had got to, and the next click started from the middle. Now the departure is written down and
  an early tick sends itself away.
- **Glass cannot cross that boundary at all.** See below.

### The one style that never hands over

Through a see-through panel, the two paths are visibly different renderers:

- the mesh drops the faces where two panels meet, because on an opaque door nobody can see them
  — through glass, those faces are what shows there is a second panel behind the first, and
  arriving turned two panes into one;
- the mesh drops faces against neighbouring blocks and the renderer keeps them, because the
  renderer is shown a world containing only the panel;
- and the rebuild delay above becomes a double blend rather than a hole: drawn twice, glass goes
  momentarily solid.

None of that can be bridged, so `DoorStyle.drawnByRenderer()` makes the glass sliding door
**always** the renderer's job, at rest as much as in flight. It costs a block entity drawn every
frame instead of geometry batched into the chunk — which is what a chest costs — and it buys a
door that looks the same standing still as it does moving.

It also cost the cracks. The game spreads breaking damage over the chunk's mesh, and a block
that is not in the mesh shows none: the door broke in the same time and dropped the same item,
but nothing on it answered being hit, which reads as a block that cannot be broken. The renderer
now draws them itself, with the same call the game makes for any other block.

### Fusuma, not shoji

The style was drawn from photographs the author supplied: **fusuma** — solid papered panels in a
plain wooden frame — rather than the translucent latticed shoji it started as. Twelve woods, plus
a glass one in neutral tones. Both need a **sliding track**, a component of its own, so that a
door which runs in a groove costs something a hinged door does not:

| | 3×3 grid | Legend |
|---|---|---|
| Sliding track ×4 | `LsL` | L = planks, s = stick |
| Fusuma ×2 | `TT / PP / FF` | T = track, P = paper, F = that wood's planks |
| Sliding glass ×2 | `TT / PP / FF` | T = track, P = glass, F = iron ingot |
| Width 4 | `DD` | D = two of the 2-wide door |

Two columns rather than three, at the author's request: three planks in a column make a slab, and
a recipe should not read like one.

---

## D-38 — The state space is a budget, and we were overdrawn (supersedes D-22)

Every blockstate is an object the game builds at start-up and keeps for the session. Vanilla's
whole block set comes to roughly 27,000 of them. This mod, at v0.3.0-dev, had **173,568** — more
than six times the game it is a mod for, from 226 doors. Start-up was measurably slower with the
mod installed, which is what made it visible.

None of it was carelessness in the ordinary sense. Each property was added for a real reason and
declared in the one place all doors share, and the multiplication happened quietly afterwards.
That is the lesson worth keeping: **a property costs its own values times every door that does
not need it.**

Five cuts, all the same shape — a property is declared by the doors that read it, and by no
others:

| Change | Carried by | Was | Became | Saved |
|---|---|---|---|---|
| `SLIDING` → `SlidingDoorBlock` | 26 of 226 | 173,568 | 96,768 | 76,800 |
| `SWING`'s third value → `SpringDoorBlock` | 24 of 226 | 96,768 | 67,584 | 29,184 |
| `PART` per width | 182 of 226 | 67,584 | 45,056 | 22,528 |
| `HINGE` off `SPLIT` doors | 101 of 226 | 45,056 | 29,824 | 15,232 |
| `POWERED` off spring doors | 202 of 226 | 29,824 | **28,096** | 1,728 |

**84% gone, and nothing about the doors changed.** No behaviour, no model, no recipe: every state
removed was one that could never occur, or one that no code ever read — a hinged door mid-slide,
an ordinary door open on its wrong side, a 1-wide door's column 3, a hinge on a leaf that pivots
about its own end, a signal on a door that ignores signals.

For scale: 226 articulated doors now cost about the same as the entire vanilla block set, and the
whole reduction is invisible from inside the game.

### The last two are dead state, not a trade-off

The first three moved a property to the doors that need it. These two removed properties that
**nothing reads**, which is a different and more embarrassing kind of waste:

- `DoorLayout.pivotAtLowEnd` answers `part < width / 2` under `SPLIT` and never consults the
  hinge. A door that opens from the middle is symmetric about that middle: each leaf turns about
  its own outer end, and there is no side for a hinge to be on. The property was two values that
  produced identical geometry and identical models, on 125 doors.
- D-36 already established that a spring door ignores redstone entirely — a latchless hinge has
  no position it can be held in. `POWERED` was written at placement and read by nobody.

Both are reversible by construction: if a future gate wants an asymmetric split leaf, that is a
new `DoorMode`, and a mode that needs a hinge declares one. The rule is per shape, not per class.

`HINGE` is decided by the mode, which — like the width — the block does not yet know when
`createBlockStateDefinition` runs, so the handover carries both. `POWERED` needed no handover:
being spring-loaded *is* the class, so `SpringDoorBlock` overrides `recordsSignal()`.

### Why the first two are classes

A subclass for one property looks disproportionate until the arithmetic is done. `SlidingDoorBlock`
exists to hold one boolean, and that boolean was costing 76,800 states so that 26 doors could
use it. The class is the cheapest way to say *this property belongs to these doors*, and it also
says it where a reader will find it.

### Why `PART` is a static array and a `ThreadLocal`

This is the part that is not pretty, and it supersedes **D-22**, which accepted `part = 0..3` on
every door because `createBlockStateDefinition` runs from `Block`'s constructor — before any field
of ours exists. That reasoning was correct; the conclusion cost 22,528 states in columns that can
never be occupied. A 1-wide door carried three unreachable states for every real one.

The two ways out:

- **A class per width.** Honest, and it multiplies: four for plain doors, four for sliding, four
  for spring — twelve today, and four more for every family added afterwards. With gates planned,
  this is a tax on every future door.
- **Tell the class its shape just before it is built.** One `ThreadLocal<Shape>` holding the
  width and the mode, set by `WideDoorBlock.sized(width, mode, factory)` around a single
  synchronous construction, read by `createBlockStateDefinition`, cleared in a `finally`. It does
  not grow with the number of door families.

The second was chosen, with one condition: **it must fail loudly.** A hidden static that silently
does the wrong thing when someone constructs a door the other way would be a far worse bug than
the states it saves. So `createBlockStateDefinition` throws outright if it is asked for a
definition with no shape in flight, and every construction path — registration in
`DoorVariant.createBlock`, and all four `MapCodec`s — goes through `sized`. A door built by any
other route fails at once, at start-up, with a message naming the fix.

The visible cost is in datagen: `DoorwayBlockStateProvider` dispatches on five properties, or
four, or four again without the hinge, because a door declares only what it reads. Three
branches, paid once, in the one place that has to enumerate every state anyway.

### The standing rule

Requested explicitly, and recorded here rather than in a comment: **all code is to be written to
be optimised and to scale.** The doors in this mod are the first family, not the last — gates are
planned, and they are wider and more complex. A cost that multiplies across 226 doors will
multiply across 400. Every new property is now to be justified against the doors that will carry
it and not use it.

---

## D-36 — Saloon doors swing both ways, and shut by themselves

A saloon door hangs on **double-acting spring hinges**. That is one mechanism, and it is worth
naming it as one, because it explains three behaviours that would otherwise look like three
unrelated features:

- it swings to either side, away from whoever pushes it;
- it returns to its frame on its own, after 40 ticks;
- it ignores redstone entirely.

The last is not a shortcut. A spring hinge has no latch -- there is no position it can be held
in. A signal saying "stay open" and a spring saying "come back" would only fight, and whichever
won would make the other look broken. `DoorStyle.springLoaded()` is therefore a single flag, and
the recipe pays for it with two iron nuggets flanking the hinge.

### Three states, not a boolean

`OPEN` had to become `SWING`: `closed`, `out`, `back`. Every part reconstructs the whole door by
subtracting its own offset from its own position (§3), and that offset depends on which way the
leaf swung. A column that could not tell the two apart would not find the rest of its door.

It had to **replace** the boolean rather than sit beside it. `PropertyDispatch` holds five
properties and the dispatch already used all five. A sixth could not have been generated at all.

Two other things fell out of the change. `newlyOccupied` can no longer derive where the door is
coming from — `!target` means nothing with three states — so it names both ends; and `released`
turned out to be `newlyOccupied` with the ends swapped, which removed a near-duplicate loop.

> **A door never crosses from one side straight to the other.** Every transition has `CLOSED` at
> an end. The blocked-position check compares the two ends and nothing in between, which is only
> sound because of that.

### The same mistake, three times

Three defects shipped between writing this and looking at it, and they were one mistake wearing
different clothes: **the model says where the leaf points, the geometry says where the columns
are, and I kept deriving one from the other.**

| What I did | What happened |
|---|---|
| Centred the leaf, arguing it made the swings symmetric | It never did — a flush leaf rotated ±90° already lands on the two opposite faces. What centring actually bought was the leaf hanging in the middle of its frame, which is an aesthetic reason, and I sold it as a necessity |
| Kept the centred box for the open states too | A blockstate turns a model about the centre of its **block**, not about the hinge. A centred box rotated 90° stays centred: a bar across the doorway, attached to nothing |
| Derived the leaf's rotation from the swing direction | A hinge does not move when the door is pushed the other way. The leaves ended up meeting in the middle of the opening |

None of these failed a test. All three were caught by looking at the door.

### What vanilla already knew

Two of the fixes were already sitting in the vanilla jar.

A door needs **two models per half**, not one: `door_bottom_left` and `door_bottom_left_open`
differ only by the west and east UVs being swapped. Opening turns the leaf the other way about
its hinge, which reverses which end of the texture faces the frame; with one model the ironwork
jumps to the free end the moment the door opens. Our rotations already matched vanilla's exactly
(270 closed, 0 and 180 open) — only the second model was missing.

The other was a hole. The narrow end faces of a leaf sample the three columns at the texture's
**edge**, which the vanilla template can do because its box always spans the full width. An arch
step does not: it stops partway, and the texture's edge columns are transparent at the rows an
arch step covers. The steps had no end caps, and an open door read as hollow from the side. They
now sample the three columns beside their own end. The spindle gaps moved one column across to
keep those columns solid, and became symmetric in the process — which the arch already was.

### What the centred leaf still costs

The leaf hangs centred while closed and flush once swung, which fixes the shape but not the
hinge. With the closed leaf centred, the pivot lies in the middle of the block; open, the leaf
spans the full depth, so the pivot is in the middle of its **length** rather than at an end. The
ironwork is drawn at a texture edge, so it lands at an end regardless. Both hinges show from the
side the door was placed from, one from the other side.

Hanging the leaf flush would resolve it completely, and was offered. Keeping it centred was
chosen deliberately: the door hanging in the middle of its frame is what the shape is.

> **At width 2 the two swings are indistinguishable once open.** The leaf does not translate
> (D-07): it lies in its own column, spanning the full depth, with no room left to say which
> side of the wall it travelled through. Width 4 shows the difference plainly, because there the
> leaf tips move. Vanilla makes the same compromise with every door it draws.

### Saved worlds do not survive this

`open` no longer exists as a property. A door saved by 0.1.0 loads with the default value while
its blocks sit where the old state put them — the project's central failure, arrived at from a
new direction. Doors placed in 0.1.0 may need replacing, and the changelog says so. A fixer was
considered and judged not worth it for a mod one version old.

---

## D-35 — Three more styles, and why style became an enum

Glass, saloon and bookshelf doors join the solid and glazed ones. **200 doors in five styles.**

Style was a boolean while there were two of them. It could not stay one: the new styles are not
available everywhere, and each answers a different question.

| Style | Materials | Widths |
|---|---|---|
| `SOLID`, `GLAZED` | all 21 | 1–4 |
| `FULL_GLASS` | none — glass is glass | 1–4 |
| `SALOON` | 12 woods; no iron, no copper | 2 and 4 |
| `BOOKSHELF` | none | 1–4 |

`DoorStyle` declares its own materials and widths, and `materials.py` mirrors it. Those two
tables agreeing is what keeps a door from pointing at a texture nobody wrote, and nothing in
the build enforces it — hence `tools/check_assets.py`, which does.

### A box has a flat top; an arch does not

A saloon door's arch cannot be drawn by transparency alone. The leaf model was a full-height
slab, so the top face stayed at two blocks whatever the texture did — a bar hanging over the
doorway, disconnected from the panel, one per column. Cutting the box to the panel fixed the
bar and lost the arch; dropping the top face fixed both and made the door invisible from above.

The arch is built as **stacked boxes** instead: a base up to the lowest point, then one layer
per step above it, each shorter along z than the one below. Every layer keeps its top face, so
the silhouette follows the arch from any angle.

The nesting is not cosmetic. If two boxes shared a face in the same plane they would z-fight,
which reads far worse than the bar did. Because each layer is strictly narrower than the one
under it, no two vertical faces ever coincide — and the upper and lower halves omit the faces
where they meet, for the same reason the vanilla door template does.

> **The arch is symmetric within each column, peaking at the middle.** An arch that rose toward
> one end looked straight from the opposite one: at an oblique angle the far rise hides behind
> the near edge. Symmetry removes the question rather than answering it.

### Copying beats redrawing

The first bookshelf door had hand-drawn spines, with the colours sampled from the vanilla
texture by frequency so they would at least be the right colours. Side by side with a real
bookshelf it was obviously wrong.

It now uses the vanilla texture unchanged. The sampling helper was deleted with it.

> **When the thing already exists in the jar, use it.** Sampling a palette is the right tool
> for a colour; it is the wrong tool for a pattern.

---

## D-34 — Datagen only where logic was duplicated

`tools/gen_assets.py` generated 1607 files, and **one** of those groups was dangerous. The 168
blockstates came out of these functions:

```python
def pivot_at_low_end(width, mode, hinge, part):
    """Do not use `hinge_part == 0`: at width 1 both ends are
       the same column and that test cannot tell the hinges apart."""
    return hinge == "left" if mode == "SINGLE" else part < width // 2
```

A line-for-line transcription of `DoorLayout.pivotAtLowEnd`, bug comment and all.
`leaf_direction` duplicated `WideDoorGeometry.leafDirection` the same way. Two definitions of the
most fragile rule in the project, with nothing forcing them to agree: fixing the geometry in Java
would leave the models drawing by the old rule, and the door would **behave** one way and **look**
another. Neither the 1016 assertions, nor the GameTests, nor the startup check would catch it —
each side would be internally correct.

`DoorwayBlockStateProvider` calls the real `DoorLayout`. The two cannot disagree.

**Only the blockstates changed owner, and that is deliberate.** Textures can never come out of
datagen — it emits JSON, not PNG — and models, recipes, loot and lang are data repetition, not
logic. Porting everything would be churn: the criterion is not "how many files" but "where is a
rule written twice".

Two things the API imposed, both found by reading rather than guessing:

- `PropertyDispatch` accepts at most **five** properties and the door has six. It only fits
  because `POWERED` was already deliberately omitted from the JSON (D-24).
- `ModelProvider` generates, by default, an item definition pointing at the block model with the
  same name — the vanilla convention. Here that produced `doorways:block/acacia_doorway_1`, which
  does not exist: block models are per **role** (`_bottom_left`, `_top_mid`) and shared across
  widths. It is now registered explicitly.

> **Diff before replacing.** The 168 blockstates came out identical to the existing files,
> which confirmed both the port and that the two geometries had not yet diverged. The 168 item
> definitions came out wrong: replacing without comparing would have swapped good files for
> broken ones and cost the doors their inventory icons.

Output goes to `build/datagen` and is **copied** into the resources rather than generated there
directly. Datagen deletes whatever it stops generating, and that folder is shared with the
texture generator's 1200+ files. A copy never deletes anything.

---

## D-33 — GameTests: where this project's bugs actually live

The 1016 geometry assertions caught **not a single one** of the ten bugs found in game. That is
no accident: `DoorLayout` is a pure function of coordinates and was never wrong. The bugs all
lived at the boundary with the world — a door that has moved is no longer where the world expects
it to be.

Five scenarios in `com.doorways.test.DoorwayScenarios`, one per bug that actually happened:

| Scenario | Bug it guards |
|---|---|
| `opensAndClosesWidthFour` | displacement: leaves the frame and returns, leaving it empty |
| `pillarInPathSurvives` | erased pillars — it must not open **and** the stone must survive |
| `redstoneOpensAndCloses` | the displaced door that never closed (5-tick polling) |
| `copperConversionSpreadsToWholeDoor` | the `onPlace` propagation (D-31) |
| `widthOneRespectsHinge` | width 1 always opened to the same side |

Bodies are shared, in pure vanilla API; only registration differs per loader — the `@GameTest`
annotation on Fabric, `RegisterGameTestsEvent` on NeoForge. Same cut as D-32.

Doors are placed through the block's own `setPlacedBy`, never column by column: assembling them
by hand would bypass the transaction guard (D-29) and test a door the game never produces.

On the build side we use Loom's `fabricApi.configureTests` rather than a hand-rolled run config —
same reason as D-30. Task: `gradlew :fabric:runGameTest`.

> **A failing test does not tell you which side is wrong.** The first version of the width-1
> scenario compared *positions* and failed against correct code: a width-1 door **does not move
> when it opens**. `openOffset` returns zero because the pivot is the only column there is — it is
> a vanilla door, the leaf rotates inside its own block. The hinge shows in the *shape*
> (`leafDirection`), not the position. I had a 50% chance of "fixing" `pivotAtLowEnd`, which was
> correct.

---

## D-32 — The `Registrar` passes factories, not instances

On NeoForge the block registry is already **frozen** when the mod is constructed. Instantiating a
block early blows up:

```
IllegalStateException: Registry is already frozen
    at Block.<init>   ← createIntrusiveHolder
```

The first version of the interface took the constructed object. That works on Fabric, where the
registries are still open at startup, and is **impossible** on NeoForge. The abstraction was
right; it was at the wrong level — it passed the object when it should have passed the recipe for
building it:

```java
Supplier<Block> block(ResourceKey<Block> key, Supplier<Block> factory);
```

Each loader calls the factory when it suits: immediately on Fabric, during the deferred
registration phase on NeoForge. `DeferredBlock` already *is* a `Supplier`, so it fits with no
adapter.

**The lesson generalises:** between loaders, what differs is rarely *what* — it is *when*. An
interface that hands over values fixes the moment; one that hands over factories does not.

---

## D-31 — Copper conversions react, they do not intercept

The axe and the honeycomb swap **one** block. On a wide door that left one column scraped and the
others oxidised. The first solution intercepted both items in `useItemOn` and converted the whole
structure. It was wrong for two independent reasons, and both only showed up in game:

1. **A block's `useItemOn` is skipped while the player is crouching** — which is precisely how
   copper is scraped in vanilla. The interception only caught the non-crouching case, and in that
   case it *did the wrong thing*: with an axe in hand a vanilla door **opens**, it does not
   scrape.
2. **`HoneycombItem.WAXABLES` is a static field.** Fabric injects into it; NeoForge does not — it
   uses data maps and patches whoever reads them (`DataMapHooks`). Reading the field by hand
   returned `null` and honeycomb did absolutely nothing on NeoForge.

The interception was removed. The item is left to do what it knows, and we react to the result in
`onPlace`: if a position went from one door of the same shape to another with nothing else
changing, that was a conversion — and the other columns follow.

```java
oldState.getBlock() instanceof WideDoorBlock old && old != this
        && old.width == width && old.mode == mode
        && /* FACING, HINGE, HALF, OPEN and PART all equal */
```

Placing, opening and closing never produce this signature: either the previous block is not a
door, or it is this same block. The transaction guard (D-29) prevents recursion while
`convertStructure` rewrites the remaining columns.

Sound, particles, tool damage and honeycomb consumption are now the item's own — I neither
reimplement them nor can they diverge from vanilla. And one hook serves both loaders, instead of
one lookup per loader.

> **Reacting to the final state is cheaper than enumerating the paths that reach it.** There were
> two (item, and item-while-crouching) and I knew about one.

`CopperDataMapCheck` is the safety net under the NeoForge half of this. It walks the 32 copper
families on every data map load and requires `waxables`, its inverse, and `oxidizables` to resolve
to the right doors. It exists because **nothing failed at startup**: the JSON was correct, the
doors registered, the data maps loaded — only the link between the two halves did not exist, and
that was visible solely by trying to wax a door in game.

The Fabric GameTests could not catch this — data maps only exist on the NeoForge side.

Two things the first version got wrong, both expensive:

**Priority.** NeoForge itself listens to `DataMapsUpdatedEvent` to derive the *inverse* maps. At
normal priority our listener ran **before** it and asked about links that did not exist yet: 32
invented failures, all from the inverse, none from the forward map. The pattern in the log was the
diagnosis — when only the derived half fails, the problem is ordering, not data. Registered with
`EventPriority.LOWEST`.

**Never throw.** The check threw an exception in development, "so it would not go unnoticed". The
result was worse than the defect it was hunting: the exception propagates up through datapack
loading, which prevents opening any world, prevents **Safe Mode** — because the mod is still
loaded — and hangs new world creation. It only logs.

> **A check must not be more destructive than the defect it looks for.** A door that will not wax
> is cosmetic; making the game unreachable is not. And a diagnostic that depends on listener order
> has to fail cheaply, because sooner or later it will be wrong.

---

## D-28 — What differs between loaders, and what does not

`common` contains **not a single import** from Fabric or NeoForge. Everything loader-specific
fits in a handful of things:

| | Fabric | NeoForge |
|---|---|---|
| Registration timing | open at startup | frozen; requires deferral (D-32) |
| Registration | `Registry.register` at startup | `DeferredRegister` + event bus |
| Creative tab | `FabricCreativeModeTab.builder()` | `DeferredRegister<CreativeModeTab>` |
| Oxidation and waxing | `OxidizableBlocksRegistry` (code) | **data maps in JSON** |

The loop over the 168 doors lives in `common`, behind a two-method interface
(`DoorwaysContent.Registrar`). Each loader supplies only the "how to register".

**NeoForge's data maps live in the `neoforge` namespace**, not ours, because NeoForge is what
reads them. They are inert on Fabric — a file in a namespace Fabric does not know is simply
ignored — so the same `common` serves both loaders with no conditionals and no duplicated
resources.

---

## D-29 — Transaction guard is per-thread, not global

The D-08 flag went from `static boolean` to `ThreadLocal`.

On a dedicated server it makes no difference: there is only the server thread. But in
**singleplayer the client and the integrated server run on different threads over the same
world**, and a shared flag would let one side clear the guard while the other was mid-transaction
— firing the integrity guards at the exact moment the structure is inconsistent on purpose.

It changes no behaviour; it isolates the threads. Found while reviewing the code for server use,
not from a symptom.

---

## D-27 — Names

`Oak Wide Door (2)` → **`Oak Doorway ×2`**, and the id `oak_wide_door_1` → `oak_doorway_1`.

"Doorway" follows vanilla's idiom (*Door*, *Trapdoor*), is immediately recognisable as
non-vanilla, and is the mod's name. The `×N` reads as width at a glance; width 1 takes no suffix.

With 168 doors, the Building Blocks tab would be unusable: they get **their own tab**, with the
hinge at the head.

---

## D-24 — Redstone enters v0.1 (reverses §1, §12 and D-03)

The specification excluded redstone from v0.1 in three places. It is **in**, by explicit request:
the doors react to pressure plates, levers, buttons and signal in general.

- New `POWERED` property. It exists to detect signal **edges** — without it, a door opened by hand
  would close on the first neighbour update that came along.
- The signal is read across **every column**, in both halves: the door is one unit, and a plate in
  front of a 4-wide door has to work no matter which column it sits against.
- **The signal is always read on the *closed* footprint**, even with the door open. The frame is
  the reference, and the frame does not move.

  Reading at the current positions put the door into **infinite oscillation**: on a 3-wide door,
  opening moves the leaf to the hinge line and the opposite column becomes air — the plate sitting
  there stopped touching the door, the signal dropped, the door closed, the plate touched it
  again, and it repeated until the server aborted the chain (`Too many chained neighbor updates`,
  ~2.4 s of lag per cycle). Found in game.
- A change to `POWERED` alone neither demolishes nor rebuilds the structure, and fires no
  neighbour flush: nothing moved, and the flush would only feed new chains.
- A door that is **open and out of its frame polls the signal every 5 ticks**.

  Reading the closed footprint fixed the oscillation but not the other half of the problem: a door
  that leaves its frame no longer has blocks touching the signal source, so **no signal change
  reaches it by neighbour update**. It showed up twice, both times only at width 3 — the only
  width that abandons the entire frame line except the hinge column:

  1. Opened by the plate, it would not close when the signal dropped.
  2. Opened by hand, it then went deaf to the plate.

  The second appeared because in the first fix I only scheduled polling when redstone had done the
  opening. The right condition is not "who opened it", it is **"is it out of its frame?"**.

  Cost: only the widths that displace blocks (3 and 4) poll, and only while open. Widths 1 and 2
  never leave the frame and never schedule anything.
- Placing the door next to an already-active signal places it open, as in vanilla.
- If the signal says open but the leaf is obstructed, `POWERED` is stored anyway to consume the
  edge. Otherwise every neighbour update would retry and replay the blocked sound.
- Opening by redstone and opening by hand go through the **same code path**, so there are not two
  versions of D-08's atomic transaction.

**Cost in states: none in the assets.** `POWERED` does not affect the model, so the blockstate
keys omit it and each variant serves both values. That is 256 states per block but still 128 JSON
variants — exactly what vanilla `oak_door` does (32 variants for 64 states).

Out of scope, as before: timers, configurable delays and sliding doors.

---

## D-25 — What the leaf may break when opening

Replaces the "replaceable" test with the **piston rule**: the leaf passes through anything with
`PushReaction.DESTROY`, breaking it with a drop.

The previous test (`canBeReplaced`) covered tall grass but not flowers or carpets — a poppy is not
replaceable — and the door refused to open against a flower. `PushReaction.DESTROY` covers all
three, plus torches and buttons, and is a rule rather than a list of cases.

Passing through without breaking is not possible: the door's columns are real blocks, and two
blocks cannot occupy the same position. That is what makes collision and pathing work.

---

## D-23 — The clicked column is the hinge column

The door grows from the clicked column, away from the hinge:

| Hinge | Grows | Rotation axis |
|---|---|---|
| Left | rightwards | clicked column |
| Right | leftwards | clicked column |

The hinge comes from where you click within the block, as in vanilla.

Previously the clicked column was always `PART 0` and the door only grew to the right — in a tight
N-block opening it could only be placed from the leftmost block, and the rotation axis was always
on the same side. Consequence: `setPlacedBy` cannot assume the block it receives is `PART 0`; it
has to reconstruct the origin from the state's `PART`.

At widths **2 and 4** (`SPLIT`) the hinge remains without effect (D-05): each half pivots at its
outer end and they always open from the centre.

---

## D-19 — The real 26.2 API (verified against decompiled sources)

Taken from `DoorBlock.java` and `Blocks.java` of decompiled Minecraft 26.2, not from memory or
blogs. **`DoorBlock` is the direct reference for `WideDoorBlock`.**

### Renames that catch anyone coming from older versions

| Before | Now |
|---|---|
| `ResourceLocation` | **`Identifier`** (`net.minecraft.resources.Identifier`) |
| `valueLookupBuilder` | **`Properties.setId(ResourceKey<Block>)`** |
| ids next to the objects | `BlockIds`, `ItemIds`, `BlockItemIds` (`ResourceKey` only) |

Nullability annotation: **`org.jspecify.annotations.Nullable`**.

### Registering a block

```java
Block block = factory.apply(properties.setId(id));   // id : ResourceKey<Block>
Registry.register(BuiltInRegistries.BLOCK, id, block);
```

`setId` is mandatory before constructing the block.

### Vanilla oak door properties — the model for ours

```java
BlockBehaviour.Properties.of()
    .mapColor(OAK_PLANKS.defaultMapColor())
    .instrument(NoteBlockInstrument.BASS)
    .strength(3.0F)
    .noOcclusion()
    .ignitedByLava()
    .pushReaction(PushReaction.DESTROY)
```

With `new DoorBlock(BlockSetType.OAK, p)` — `BlockSetType` brings open/close sounds and
`canOpenByHand`. **Directly reusable**, and satisfies §8 (vanilla resources).

Note: `pushReaction(DESTROY)` settles §12 ("piston compatibility") for free.

### Signatures WideDoorBlock overrides

```java
protected VoxelShape getShape(BlockState, BlockGetter, BlockPos, CollisionContext)
protected BlockState updateShape(BlockState, LevelReader, ScheduledTickAccess, BlockPos,
                                 Direction, BlockPos, BlockState, RandomSource)
public    BlockState playerWillDestroy(Level, BlockPos, BlockState, Player)
protected boolean    isPathfindable(BlockState, PathComputationType)
public    @Nullable BlockState getStateForPlacement(BlockPlaceContext)
public    void       setPlacedBy(Level, BlockPos, BlockState, @Nullable LivingEntity, ItemStack)
protected InteractionResult useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)
protected void       neighborChanged(BlockState, Level, BlockPos, Block, @Nullable Orientation, boolean)
protected boolean    canSurvive(BlockState, LevelReader, BlockPos)
protected BlockState rotate(BlockState, Rotation)
protected BlockState mirror(BlockState, Mirror)
protected void       createBlockStateDefinition(StateDefinition.Builder<Block, BlockState>)
```

Every block also needs a `MapCodec` and `codec()`.

### Valuable confirmations

- **Vanilla's opening rotation is ours.** In `getShape`:
  `OPEN ? (HINGE == RIGHT ? getCounterClockWise() : getClockWise()) : facing`.
  It matches the geometry derived in D-04/`core`: LEFT → clockwise, RIGHT → counter-clockwise.
- **`useWithoutItem` returns `InteractionResult`** (not `ItemInteractionResult`).
- **The single drop already has vanilla precedent:** `DoublePlantBlock.preventDropFromBottomPart`,
  called from `playerWillDestroy`. That is the pattern D-09 follows.
- **`canSurvive`:** the lower half requires `belowState.isFaceSturdy(level, below, Direction.UP)`;
  the upper requires `belowState.is(this)`. Confirms the rule chosen in D-10.
- `level.setBlock(pos, state, 10)` in vanilla toggles — the flags matter for D-08.
- Shapes: `Shapes.rotateHorizontal(Block.boxZ(16.0, 13.0, 16.0))` builds all 4 orientations from
  one. Saves us the work on §15's four orientations.

### ⚠️ Multi-loader trap

NeoForge's decompiled sources are **Minecraft already patched by NeoForge**. For example,
`DoorBlock` has a `getRelocability(...)` returning
`net.neoforged.neoforge.common.util.BlockRelocability` — that **does not exist on Fabric**.
Nothing taken from those sources may enter `common` without confirming it is pure vanilla.

---

## Revised work order (replaces §14)

1. ✅ `core` — pure geometry + verification of 4 widths × 4 orientations × 2 states
2. ✅ Multi-loader Gradle project (JDK 25) — `core`, `common`, `fabric`, `neoforge`
3. ✅ 1-wide door on Fabric (proof of the 26.2 registration infrastructure)
4. ✅ Widths 2, 3 and 4 on the same geometry
5. ✅ Atomic transaction (D-08) + space validation (D-06)
6. ✅ Blocked sound (D-13)
7. ✅ Integrated breaking and single drop (D-09)
8. ✅ Blockstate/model/texture generation (D-15)
9. ✅ Recipes, translations, 21 materials, redstone, copper oxidation
10. ✅ GameTests (D-16, D-33)
11. ✅ NeoForge module (D-32) — client and dedicated server
12. ✅ Blockstate datagen from the real geometry (D-34)
13. ✅ Glass, saloon and bookshelf styles (D-35)
14. ✅ Two-way, self-closing saloon doors (D-36)

Open: real datagen for the remaining JSON, a distribution jar that excludes the tests, and a
translucent render layer for the glazed variants.

---

## The gap that mattered, and what closed it

`core` has 13 tests and 1016 assertions, and **none of them touch the game**. Every real bug in
this phase appeared exactly there, and none was visible in pure geometry:

| Symptom in game | Cause |
|---|---|
| Door grew upward when clicking the top half | Origin did not normalise `HALF` |
| No door made a sound when opening | `playSound(player, …)` excludes that player on the server |
| 3-wide door would not open in grass | Opening required air; placement accepted replaceables |
| 1-wide door always opened to the same side | Direction decided by `hingePart == 0` |
| Infinite oscillation with a pressure plate | Signal read at current positions, which move |
| 3-wide door would not close when leaving the plate | Displaced, it stops receiving neighbour updates |
| Pillars erased on opening | State said "open" with blocks on the closed footprint |
| Oxidation on a single column | `changeOverTime` swaps one position; a door is several |
| Crouching axe scraped one column only | A block's `useItemOn` is skipped while crouching |
| Honeycomb did nothing on NeoForge | Lookup read from a static field only Fabric populates |

The pattern is always the same: **a door that has moved is no longer where the world expects it to
be.** The GameTests in D-33 are what catches this entire class, and the five that exist were
chosen straight from this table.
