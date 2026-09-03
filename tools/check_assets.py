"""Checks that the generated asset tree is internally consistent.

Two generators write into the same tree: datagen owns the blockstates and item definitions,
because they derive from geometry, and this directory's script owns everything else. They agree
on names only by both following the same style table -- one in DoorStyle.java, one in
materials.py. Nothing forces those two to stay in step.

A disagreement is quiet. A door whose blockstate points at a model nobody wrote renders as the
missing-texture cube; a door with no loot table drops nothing. Neither fails the build.

    python tools/check_assets.py <project-root>

Exits non-zero on the first inconsistency, so it can gate a release.
"""
import io
import json
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "common", "src", "main", "resources")
ASSETS = os.path.join(BASE, "assets", "doorways")
DATA = os.path.join(BASE, "data", "doorways")

problems = []

# The parts a door is built from, which are items rather than doors. Mirrors DoorwaysContent.
COMPONENTS = {"iron_hinge", "sliding_track"}


def names(*path):
    directory = os.path.join(*path)
    if not os.path.isdir(directory):
        problems.append("missing directory: " + directory)
        return set()
    return {f.rsplit(".", 1)[0] for f in os.listdir(directory)
            if os.path.isfile(os.path.join(directory, f))}


def expect(condition, message):
    if not condition:
        problems.append(message)


def report(label, offenders, verb="missing"):
    if offenders:
        problems.append(f"{label}: {len(offenders)} {verb}, e.g. {sorted(offenders)[:5]}")


doors = names(ASSETS, "blockstates")
expect(doors, "no blockstates found -- run gradlew :fabric:runDatagen")

# Every door needs the full set. The blockstates come from datagen, the rest from gen_assets.
report("loot tables", doors - names(DATA, "loot_table", "blocks"))
report("item definitions", doors - names(ASSETS, "items"))
report("item models", doors - names(ASSETS, "models", "item"))
report("item textures", doors - names(ASSETS, "textures", "item"))

# Doors that only one side knows about.
report("blockstates without a loot table", doors - names(DATA, "loot_table", "blocks"))
report("loot tables without a blockstate", names(DATA, "loot_table", "blocks") - doors)

# Every model a blockstate points at has to exist, and every texture a model asks for has to
# exist too.
#
# The texture half used to compare the two directory listings by name, which assumed a model and
# its texture are always called the same thing. A saloon door broke that: it has two models per
# leaf -- centred in its frame, flush once swung -- sharing one texture, because only the box
# moves. Reading the reference out of the model checks what actually breaks instead of policing
# a naming convention.
block_models = names(ASSETS, "models", "block")
block_textures = names(ASSETS, "textures", "block")
referenced = set()
for door in doors:
    with io.open(os.path.join(ASSETS, "blockstates", door + ".json"), encoding="utf-8") as f:
        for variant in json.load(f)["variants"].values():
            referenced.add(variant["model"].rsplit("/", 1)[-1])
report("models referenced by a blockstate", referenced - block_models)

wanted = set()
for model in block_models:
    with io.open(os.path.join(ASSETS, "models", "block", model + ".json"), encoding="utf-8") as f:
        for texture in json.load(f).get("textures", {}).values():
            # "#face" points back into the same model; a minecraft: path is vanilla's problem.
            if texture.startswith("#") or not texture.startswith("doorways:"):
                continue
            wanted.add(texture.rsplit("/", 1)[-1])
report("textures a block model asks for", wanted - block_textures)

# Nothing should be generating textures no model ever names.
report("block textures", block_textures - wanted, "generated but never used")

# Every door should be nameable in the creative menu.
with io.open(os.path.join(ASSETS, "lang", "en_us.json"), encoding="utf-8") as f:
    lang = json.load(f)
report("translations", {d for d in doors if f"block.doorways.{d}" not in lang})

# The paintings are the one set of textures nothing points at: they are not in any model, and
# the renderer asks the atlas for them by name at the moment it draws one. The list therefore
# comes from the textures themselves, and everything else is checked against it.
# A painting spans the whole door, so it needs one canvas per width a sliding door comes in.
PAINTING_WIDTHS = (2, 4)
canvases = names(ASSETS, "textures", "block", "painting")
PAINTINGS = {canvas.rsplit("_", 1)[0] for canvas in canvases}
PAINTING_ITEMS = {"fusuma_" + painting for painting in PAINTINGS}
expect(PAINTINGS, "no paintings found -- run tools/gen_assets.py")
report("painting canvases",
       {f"{painting}_{width}" for painting in PAINTINGS for width in PAINTING_WIDTHS}
       - canvases)
report("painting item textures", PAINTING_ITEMS - names(ASSETS, "textures", "item"))
report("painting item models", PAINTING_ITEMS - names(ASSETS, "models", "item"))
report("painting item definitions", PAINTING_ITEMS - names(ASSETS, "items"))
report("painting recipes", PAINTING_ITEMS - names(DATA, "recipe"))
report("painting translations",
       {item for item in PAINTING_ITEMS if f"item.doorways.{item}" not in lang})

# Recipes may be grouped (name, name_1, name_2), so strip the suffix before matching.
for recipe in names(DATA, "recipe"):
    with io.open(os.path.join(DATA, "recipe", recipe + ".json"), encoding="utf-8") as f:
        result = json.load(f).get("result", {}).get("id", "")
    if result.startswith("doorways:"):
        produced = result.split(":", 1)[1]
        # Everything else a recipe can make is a plain item: the hinge that every swinging door
        # starts from, and the track that every sliding one does.
        expect(produced in doors or produced in COMPONENTS or produced in PAINTING_ITEMS,
               f"recipe {recipe} produces {result}, which is not a door, a component "
               "or a painting")

# A loot table may only name properties the door actually has.
#
# Naming one it does not have makes the whole table fail to parse, and a door with no loot table
# drops nothing at all -- silently, in survival, with only a line in the log at start-up. It
# happened: after `part` became one property per width (D-38), the 44 one-column doors kept a
# `part=0` condition for a property they no longer declared.
#
# The blockstate keys are the ground truth for what a door declares, with one exception: the two
# properties deliberately left out of the dispatch, which every variant serves both values of.
NOT_DISPATCHED = {"powered", "sliding"}


def properties_of(door):
    """The properties named in a door's blockstate keys."""
    with io.open(os.path.join(ASSETS, "blockstates", door + ".json"), encoding="utf-8") as f:
        return {pair.split("=")[0]
                for key in json.load(f)["variants"]
                for pair in key.split(",") if pair}


def loot_conditions(node):
    """Every block_state_property condition anywhere in a loot table."""
    if isinstance(node, dict):
        if node.get("condition") == "minecraft:block_state_property":
            yield node.get("properties", {})
        for value in node.values():
            yield from loot_conditions(value)
    elif isinstance(node, list):
        for value in node:
            yield from loot_conditions(value)


for door in sorted(doors & names(DATA, "loot_table", "blocks")):
    declared = properties_of(door) | NOT_DISPATCHED
    with io.open(os.path.join(DATA, "loot_table", "blocks", door + ".json"),
                 encoding="utf-8") as f:
        table = json.load(f)
    for condition in loot_conditions(table):
        for named in condition:
            expect(named in declared,
                   f"loot table {door} tests '{named}', which that door does not have")

# Every door has to belong to a tool, and to exactly one. Without a mineable tag an axe gives
# no bonus on a wooden door, which does not change its hardness but makes it visibly slower to
# break than the vanilla door beside it -- a difference nothing else in this file would notice.
MINEABLE = os.path.join(BASE, "data", "minecraft", "tags", "block", "mineable")
tagged = {}
for tool in ("axe", "pickaxe"):
    path = os.path.join(MINEABLE, tool + ".json")
    if not os.path.isfile(path):
        problems.append("missing tool tag: " + path)
        continue
    with io.open(path, encoding="utf-8") as f:
        for entry in json.load(f)["values"]:
            name = entry.split(":", 1)[1]
            if name in tagged:
                problems.append(f"{name} is mineable with both {tagged[name]} and {tool}")
            tagged[name] = tool
report("doors belonging to no tool", doors - set(tagged))
report("tool tags naming something that is not a door", set(tagged) - doors,
       "unrecognised")

if problems:
    print("FAILED")
    for problem in problems:
        print("  x " + problem)
    sys.exit(1)

print(f"OK - {len(doors)} doors, {len(block_models)} block models, all consistent.")
