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


def names(*path):
    directory = os.path.join(*path)
    if not os.path.isdir(directory):
        problems.append("missing directory: " + directory)
        return set()
    return {f.rsplit(".", 1)[0] for f in os.listdir(directory)}


def expect(condition, message):
    if not condition:
        problems.append(message)


def report(label, missing):
    if missing:
        problems.append(f"{label}: {len(missing)} missing, e.g. {sorted(missing)[:5]}")


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

# Every model a blockstate points at has to exist, and every model needs its texture.
block_models = names(ASSETS, "models", "block")
block_textures = names(ASSETS, "textures", "block")
referenced = set()
for door in doors:
    with io.open(os.path.join(ASSETS, "blockstates", door + ".json"), encoding="utf-8") as f:
        for variant in json.load(f)["variants"].values():
            referenced.add(variant["model"].rsplit("/", 1)[-1])
report("models referenced by a blockstate", referenced - block_models)
report("textures behind a block model", block_models - block_textures)

# Every door should be nameable in the creative menu.
with io.open(os.path.join(ASSETS, "lang", "en_us.json"), encoding="utf-8") as f:
    lang = json.load(f)
report("translations", {d for d in doors if f"block.doorways.{d}" not in lang})

# Recipes may be grouped (name, name_1, name_2), so strip the suffix before matching.
for recipe in names(DATA, "recipe"):
    with io.open(os.path.join(DATA, "recipe", recipe + ".json"), encoding="utf-8") as f:
        result = json.load(f).get("result", {}).get("id", "")
    if result.startswith("doorways:"):
        produced = result.split(":", 1)[1]
        # The hinge is a plain item, not a door, and is the one thing here that is neither.
        expect(produced in doors or produced == "iron_hinge",
               f"recipe {recipe} produces {result}, which is not a registered door")

if problems:
    print("FAILED")
    for problem in problems:
        print("  x " + problem)
    sys.exit(1)

print(f"OK - {len(doors)} doors, {len(block_models)} block models, all consistent.")
