# Store listings

The text published on GitHub, Modrinth and CurseForge, kept here so that it is versioned with
the release it describes. **Update this file in the same commit as the release it belongs to** —
a listing that drifts from the mod is worse than no listing.

The order of a release is in the checklist at the bottom.

---

## GitHub — repository description

> Articulated doors 1 to 4 blocks wide and 2 or 3 blocks tall for Minecraft 26.2 and 26.3 — 234
> of them, swinging, spring-hinged or sliding, and the sliding ones can be painted. Fabric and
> NeoForge.

---

## Modrinth and CurseForge — summary

> Wide articulated doors for Minecraft 26.2 and 26.3 — 1 to 4 blocks wide, 2 or 3 tall. 234 doors
> in eight styles, nine paintings for the sliding ones, and stone doorways with round heads.
> Fabric and NeoForge.

---

## Modrinth and CurseForge — description

Wide doors that actually open wide. One block, two, three or four — and always **one door**, not
several stacked side by side.

## Eight styles

- **Solid** — a full panel, in every wood, in iron and in all eight copper states
- **Glazed** — glass in the upper half
- **Glass** — glass from top to bottom, in an iron frame
- **Saloon** — spindles under an arched rail, open above and below, hung on
  spring hinges. Two and four blocks wide, in wood
- **Bookshelf** — a wall of books that opens
- **Fusuma** — papered panels that slide aside instead of swinging out, and that
  can be painted. Two and four blocks wide, in wood
- **Sliding glass** — the same mechanism, glazed
- **Stone** — a leaf of weathered boards strapped in iron, hung in a doorway of
  masonry. One and two blocks wide, two or three tall

234 doors in all.

## How they behave

Break any part and the whole door comes down, dropping a single item. Redstone
treats it as one unit: a pressure plate in front of a four-wide door works
wherever it sits against it.

Opening a three- or four-wide door **moves blocks** — the leaf swings out of its
frame. If something is in the way it refuses to open and says so. Grass and
flowers in the path are broken properly, with their drops.

Copper doors oxidise over time, take wax from honeycomb and are scraped back with
an axe — the whole door at once, never a single column. Iron doors cannot be
opened by hand, exactly like vanilla. Every material carries its own vanilla
sounds.

## Stone doorways stand three blocks tall

The way round a medieval door actually was: the door is **timber** and the stone
is the wall it hangs in. A leaf of weathered boards — no two cut from the same
tree, some with water run down them for years, darker at the foot where the damp
comes up — strapped twice in wrought iron.

The masonry is not painted on the door. It is **built**, and it **stays put**:
open the door and the jambs stand where they were, with the leaf swung through
them. They are solid, too — the one part of a door in this mod you can walk into.

Three rows tall, the head is a **round arch**, and the ring of stone over it
stays with the wall like the jambs do. Two rows tall it is square-headed, and
that is not a shortcut: a round head on a two-row door leaves a crown you cannot
walk under standing up.

In cobblestone and cobbled deepslate, one and two columns wide.

## Saloon doors play by different rules

They hang on **double-acting spring hinges**, and that one detail changes three
things about them.

**They swing both ways.** Push a saloon door and it goes away from you, whichever
side you are standing on. Every other door in the mod opens one way only.

**They close on their own,** about two seconds after you walk through. Stand in
the doorway and the spring waits, then shuts it the moment you step clear.

**They ignore redstone.** A spring has no latch, so there is nothing for a signal
to hold open. A powered saloon door simply stays shut — use any other style if
you want a door a circuit can open.

If the way is blocked the door refuses rather than swinging the other way. A door
that occasionally opens towards you, for reasons you cannot see, is worse than
one that will not budge.

## Sliding doors need no room to open

A fusuma does not swing, and it does **not** disappear into a cavity in the wall
either. A leaf is two panels running on two tracks: shut they stand side by side,
open one runs behind the other. The space the door occupies open is exactly the
space it occupied shut, so nothing is carved out of what you built around it.

A two-wide door leaves a doorway of one block. A four-wide one leaves two,
parting from the middle.

They **glide** rather than snapping from shut to open, and they answer both your
hand and a redstone signal.

## Fusuma can be painted

A fusuma is the wall of the room, and it has been painted for as long as fusuma
have existed. Nine paintings, each crafted from paper and the thing it depicts:

| Painting | Made with |
|---|---|
| Pine | Spruce Sapling |
| Bamboo | Bamboo |
| Cherry Blossom | Cherry Sapling |
| Autumn Maple | Leaves, any kind |
| Great Wave | Kelp |
| Waterfall | Prismarine Crystals |
| Mountain | Flint |
| Moon | Glowstone Dust |
| Koi | Tropical Fish |

Put one on with the painting in hand, and take it off with a brush — it comes
back whole, and so does the one it replaced if you paint over it.

A painting covers the **whole door**, so a four-wide door carries a wider picture
rather than the same one stretched. Opening it parts the picture down the middle,
which is exactly what a set of fusuma does.

## Crafting

Everything hinged starts from an **Iron Hinge**: two iron ingots and two nuggets,
laid diagonally, for two hinges.

A one-wide door is six of the material plus a hinge, and it makes four. Wider
doors are built from narrower ones, so a four-wide door costs exactly one batch —
six planks and a hinge for a door four blocks across. Glazed doors are a solid
door plus glass.

Wooden doors take **planks**, the same as a vanilla door, in every style.

Saloon doors add **two iron nuggets** for the springs — one above the hinge and
one below. They only come two and four wide.

Sliding doors run in a **Sliding Track** instead: two planks and a stick make
four tracks. Two tracks, two paper and two planks give two fusuma; swap the paper
for glass and the planks for iron ingots and you get the sliding glass door. Four
wide is two of the two-wide, as everywhere else.

Stone doorways are a column of stone beside a column of planks, on a hinge, for
four doors:

```
L W .
L W H
L W .
```

**L** — cobblestone, or cobbled deepslate · **W** — planks, any wood · **H** —
Iron Hinge. A three-row doorway is the two-row one of the same width plus a
course of stone per column, in any arrangement.

Paintings are two paper and one thing that names the motif, in any arrangement.

## Requirements

**Minecraft 26.2 or 26.3** — a separate file for each, so take the one that
matches your game. On Fabric it also needs **Fabric API**. Works on dedicated
servers, and is required on both the client and the server.

On 26.3, NeoForge itself is still in beta: that is the only NeoForge build for
that version, not something this mod chose.

If your launcher supplies its own Java instead of the bundled runtime, it has to
be Java 25 or newer.

## Planned

- **Chain link** — the fence and the gate both, in the three finishes real ones
  come in. Iron bars are not the same thing and never were
- **Windows** — casements and shutters that open, two blocks tall, on the same
  machinery the doors run on
- **Curtains** — cloth on the sliding mechanism, in every wool colour, and the
  first thing here you can simply walk through
- **Doors five blocks wide that part in the middle** — two and a half to each
  side, with no post between them. A leaf would have to reach half a block
  further than the blocks it is made of, which the game allows for drawing but
  not necessarily for walking into. Being looked at rather than promised

No dates. Suggestions are welcome on the
[issue tracker](https://github.com/itsandrefreitas/doorways/issues).

---

## Gallery -- the recipe sheets

Uploaded as images on Modrinth and CurseForge, not attached to the release. Regenerate them with
`python tools/gen_recipes.py .` whenever a recipe changes; they live in `Screenshots/` and are
not committed, so nothing else will warn you.

**The description field holds 256 characters.** Both of these are written to that limit, with no
em dashes -- a platform that counts bytes rather than characters would charge three for each one,
and the difference only shows when the text is silently truncated.

### `doors.gif`

**Title** -- Every door in the mod

**Description** (240 characters)

> Every door in the mod, a family per row and every size of it side by side: solid, glazed,
> saloon, fusuma, painted, all-glass, sliding glass, bookshelf and the stone doorway. It cycles
> the twelve woods, the two stones and the nine paintings.

Regenerate with `python tools/gen_showcase.py .`

### `recipes-doors.gif`

**Title** -- How a door is made

**Description** (251 characters)

> Every way a door is crafted, in one picture. Four styles on the left; joining them wider,
> glazing those, and the stone doorway on the right. Cycles the fourteen materials a body is made
> from, and each row quotes the sizes it comes in, width by height.

### `recipes-extras.gif`

**Title** -- Components and paintings

**Description** (234 characters)

> The Iron Hinge every swinging door starts from, the Sliding Track the sliding ones run on, and
> the nine paintings a fusuma can carry: two paper and the thing the picture is of. Cycles all
> nine. Both components take planks of any wood.

---

## Version changelog — 0.6.0

**Wooden doors cost planks now, and Minecraft 26.3 is supported.**

## Doors are made of planks

A wooden door took six **logs**. It now takes six **planks**, in all twelve
woods — which is what a door has been made of in vanilla since there were doors.

That makes them about four times cheaper. Wider doors are still built from
narrower ones, so a door **four blocks across is now six planks and a hinge**.
A one-wide door costs less wood than the vanilla door it is the same size as, and
the iron in the hinge is what you pay instead.

Nothing changes for doors already standing, and this applies on both game
versions.

## Minecraft 26.3, and 26.2 stays

Two files per loader from here on — take the one that matches your game. Every
door, every painting and every style behaves identically on both: doors that
slide, swing both ways, oxidise or stand in stone came through the port
untouched.

What did have to change is something you would only have noticed by losing it.
26.3 rebuilt the way loot tables describe their conditions, and a door whose
table the game cannot read **drops nothing at all** — no error, no warning. Each
version now gets its own tables, written together in one pass, and the release
check refuses a door whose table is missing on either version or written in the
other one's format.

Also in this release:

- the block codecs are gone. 26.3 removed the registry behind them, and it turned
  out they had never been required on 26.2 either — nothing in the game ever read
  a door through one, and one of them had been quietly wrong since tall doors
  arrived
- on 26.3, NeoForge itself is still in beta. That is the only NeoForge build for
  that version rather than a choice made here

---

## The release checklist

Every game version in `minecraft_targets` gets its own jars, so steps 3 to 6 run **once per
version**. `gradlew minecraftVersions` lists them. On PowerShell the flag must be quoted —
`"-Pmc=26.2"` — or the shell drops everything after the dot.

1. `mod_version` in `gradle.properties`, and `versions/<version>.properties` if a
   Fabric API or NeoForge build moved
2. `python tools/gen_assets.py .` — writes every version's loot tables in one pass
3. `gradlew :fabric:runDatagen` **and** `gradlew :fabric:runDatagen -Pmc=26.2`
4. `python tools/check_assets.py .` — green in both directions, and for every
   version. Run it after the last datagen, not between them
5. `gradlew :fabric:runGameTest` and `-Pmc=26.2`
6. `gradlew build` and `gradlew build -Pmc=26.2` — four jars, each naming its
   game version in the file name
7. `README.md` — the door count, the file and model counts, the state budget, the
   number of scenarios, and the version tables
8. `DECISIONS.md` — a numbered entry for anything non-obvious, including the
   options that were **declined** and why
9. **This file** — description, summary, and a changelog for the new version
10. `python tools/gen_recipes.py .` — **whenever a recipe changed**. It draws the recipe sheets
    into `Screenshots/`, which is not committed, so nothing warns you they have gone stale
11. Commit, tag, push
12. GitHub release, then Modrinth, then CurseForge. Upload **all four jars** and tag each with
    its own game version — a file tagged with the wrong version is installed by people it will
    not work for. The recipe sheets are uploaded as images on the store pages, not attached to
    the release
