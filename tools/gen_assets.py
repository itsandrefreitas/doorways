"""Generates the Doorways textures, models, recipes and loot tables.

Writes 16x16 PNGs with plain zlib (no PIL). Blockstates and item definitions are NOT generated
here -- they come from datagen, because they derive from geometry. See DECISIONS.md, D-34.

Each material's colours are **sampled from the vanilla textures** (palettes.py), not invented.
Run it with the client jar within reach:

    python tools/gen_assets.py <project-root>
"""
import json
import os
import struct
import sys
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from materials import (MATERIALS, block_name, display_name, oxidation_chain,
                       waxable_pairs)
from palettes import palette_from

MOD = "doorways"
HINGE = "iron_hinge"
ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", MOD)
DATA = os.path.join(ROOT, "common", "src", "main", "resources", "data", MOD)

CLIENT_JAR = os.path.expanduser(
    "~/.gradle/caches/neoformruntime/artifacts/minecraft_26.2_client.jar")

# Ironwork and glass are the same for every material: they are the mod's identity.
IRON = (146, 148, 155, 255)
IRON_HI = (186, 188, 194, 255)
IRON_LO = (98, 100, 107, 255)
GLASS = (156, 198, 214, 110)
GLASS_HI = (206, 232, 242, 150)
NONE = (0, 0, 0, 0)

W = H = 16


def blank():
    return [[NONE] * W for _ in range(H)]


def write_png(path, px):
    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in px)
    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9))
                + chunk(b"IEND", b""))


def write_json(path, body, indent=2):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(body, f, indent=indent, ensure_ascii=False)


# ---------------------------------------------------------------- drawing
def planks(px, pal):
    for y in range(H):
        for x in range(W):
            if x in (5, 10):
                px[y][x] = pal["GROOVE"]
            elif x in (0, 15):
                px[y][x] = pal["WOOD_LO"]
            else:
                px[y][x] = pal["WOOD_HI"] if (x + y) % 7 == 0 else pal["WOOD"]


def band(px, y0, y1):
    for y in range(y0, y1 + 1):
        for x in range(W):
            px[y][x] = IRON_HI if y == y0 else (IRON_LO if y == y1
                                                else (IRON if x % 5 else IRON_LO))


def strap(px, side):
    xs = (0, 1) if side == "left" else (14, 15)
    for y in range(H):
        for x in xs:
            px[y][x] = IRON_HI if y % 6 == 0 else (IRON if x in (1, 14) else IRON_LO)
    for y in (3, 8, 13):
        px[y][0 if side == "left" else 15] = IRON_HI


def window(px, pal, role):
    """A glazed opening.

    Framed only on the leaf's outer edges, so that adjacent columns form one continuous window
    rather than several separate ones.

    The opening is deliberately narrow and the frame takes the material's colour rather than
    iron, so that the upper half still shows enough of the material for copper's oxidation to
    be visible on the glazed doors.
    """
    outer_left = role in ("single", "left")
    outer_right = role in ("single", "right")
    x0 = 3 if outer_left else 0
    x1 = 12 if outer_right else 15

    for y in range(6, 11):
        for x in range(x0, x1 + 1):
            px[y][x] = GLASS
    for y in range(7, 10):
        for x in range(x0 + 1, min(x0 + 4, x1 + 1)):
            px[y][x] = GLASS_HI
    for x in range(x0, x1 + 1):
        px[5][x] = pal["GROOVE"]
        px[11][x] = pal["GROOVE"]
    if outer_left:
        for y in range(5, 12):
            px[y][2] = pal["GROOVE"]
    if outer_right:
        for y in range(5, 12):
            px[y][13] = pal["GROOVE"]


def door_texture(pal, half, role, glass):
    px = blank()
    planks(px, pal)
    if half == "bottom":
        band(px, 13, 15)
        band(px, 0, 1)
    else:
        band(px, 0, 2)
        band(px, 14, 15)
        if glass:
            window(px, pal, role)
    if role in ("left", "single"):
        strap(px, "left")
    if role in ("right", "single"):
        strap(px, "right")
    return px


def item_texture(pal, width, glass):
    px = blank()
    total = min(width * 3 + 1, 13)
    x0 = (W - total) // 2
    for y in range(2, 15):
        for x in range(x0, x0 + total):
            if x in (x0, x0 + total - 1) or y in (2, 14):
                px[y][x] = IRON_LO
            elif glass and 4 <= y <= 7:
                px[y][x] = GLASS_HI
            elif (x - x0) % 3 == 0:
                px[y][x] = pal["GROOVE"]
            else:
                px[y][x] = pal["WOOD"] if y > 8 else pal["WOOD_HI"]
    for x in range(x0, x0 + total):
        px[8][x] = IRON
    return px


def hinge_texture():
    px = blank()
    for y in range(3, 13):
        for x in range(3, 8):
            px[y][x] = IRON if (x + y) % 4 else IRON_HI
        for x in range(9, 14):
            px[y][x] = IRON_LO if (x + y) % 4 else IRON
    for y in range(2, 14):
        px[y][8] = IRON_HI if y % 3 else IRON_LO
    return px


# Door geometry does not belong in this file. Blockstates derive from DoorLayout and are
# generated by DoorwayBlockStateProvider, so that the rule has a single definition.
# See DECISIONS.md, D-34.
#
# Needing geometry here again would mean this script is generating the wrong thing: logic
# belongs in datagen, pixels belong here.


def leaf_model(face_tex):
    """Geometry copied from vanilla door_bottom_left: a 3/16 slice on the X axis, with the
    wide faces to west/east."""
    return {
        "ambientocclusion": False,
        "textures": {"particle": face_tex, "face": face_tex},
        "elements": [{
            "from": [0, 0, 0], "to": [3, 16, 16],
            "faces": {
                "west": {"texture": "#face", "uv": [0, 0, 16, 16]},
                "east": {"texture": "#face", "uv": [16, 0, 0, 16]},
                "north": {"texture": "#face", "uv": [3, 0, 0, 16]},
                "south": {"texture": "#face", "uv": [0, 0, 3, 16]},
                "up": {"texture": "#face", "uv": [0, 0, 3, 16], "rotation": 270},
                "down": {"texture": "#face", "uv": [16, 13, 0, 16], "rotation": 90},
            },
        }],
    }


# ---------------------------------------------------------------- writing
def main():
    jar = zipfile.ZipFile(CLIENT_JAR)
    lang = {"itemGroup.doorways": "Doorways", f"item.{MOD}.{HINGE}": "Iron Hinge"}
    n = 0

    for material, label, texture, craft in MATERIALS:
        pal = palette_from(jar.read(f"assets/minecraft/textures/block/{texture}.png"))

        # leaf textures and models
        for half in ("bottom", "top"):
            for role in ("single", "left", "mid", "right"):
                for glass in ((False,) if half == "bottom" else (False, True)):
                    stem = f"{material}_{'glass_' if glass else ''}doorway_{half}_{role}"
                    write_png(os.path.join(ASSETS, "textures", "block", stem + ".png"),
                              door_texture(pal, half, role, glass))
                    write_json(os.path.join(ASSETS, "models", "block", stem + ".json"),
                               leaf_model(f"{MOD}:block/{stem}"))
                    n += 2

        for width in (1, 2, 3, 4):
            for glass in (False, True):
                block = block_name(material, width, glass)
                lang[f"block.{MOD}.{block}"] = display_name(label, width, glass)

                # Blockstates and item definitions come from datagen, not from here:
                # they derive from geometry. See DECISIONS.md, D-34.
                write_png(os.path.join(ASSETS, "textures", "item", block + ".png"),
                          item_texture(pal, width, glass))
                write_json(os.path.join(ASSETS, "models", "item", block + ".json"),
                           {"parent": "item/generated",
                            "textures": {"layer0": f"{MOD}:item/{block}"}})
                write_json(os.path.join(DATA, "loot_table", "blocks", block + ".json"),
                           loot_table(block))
                n += 3

        n += write_recipes(material, craft)

    # the hinge item
    write_png(os.path.join(ASSETS, "textures", "item", HINGE + ".png"), hinge_texture())
    write_json(os.path.join(ASSETS, "models", "item", HINGE + ".json"),
               {"parent": "item/generated", "textures": {"layer0": f"{MOD}:item/{HINGE}"}})
    write_json(os.path.join(ASSETS, "items", HINGE + ".json"),
               {"model": {"type": "minecraft:model", "model": f"{MOD}:item/{HINGE}"}})
    write_json(os.path.join(DATA, "recipe", HINGE + ".json"), {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "key": {"I": "minecraft:iron_ingot", "n": "minecraft:iron_nugget"},
        "pattern": ["In", "nI"],
        "result": {"count": 2, "id": f"{MOD}:{HINGE}"},
    })
    # Waxing at the bench, as well as honeycomb in hand -- vanilla offers both routes.
    for bare, waxed in waxable_pairs():
        for width in (1, 2, 3, 4):
            for glass in (False, True):
                src, dst = block_name(bare, width, glass), block_name(waxed, width, glass)
                write_json(os.path.join(DATA, "recipe", dst + "_from_honeycomb.json"), {
                    "type": "minecraft:crafting_shapeless",
                    "category": "building",
                    "group": dst,
                    "ingredients": [f"{MOD}:{src}", "minecraft:honeycomb"],
                    "result": {"id": f"{MOD}:{dst}"},
                })
                n += 1

    n += write_neoforge_data_maps()
    write_json(os.path.join(ASSETS, "lang", "en_us.json"), dict(sorted(lang.items())))
    n += 5

    print(f"{n} files, {len(MATERIALS)} materials, {len(MATERIALS) * 8} doors")


def loot_table(block):
    """Only the anchor (lower half, column 0) drops anything: this prevents duplication when
    an explosion catches several columns. The same trick vanilla oak_door uses, widened to
    cover PART."""
    return {
        "type": "minecraft:block",
        "random_sequence": f"{MOD}:blocks/{block}",
        "pools": [{
            "rolls": 1.0,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{
                "type": "minecraft:item",
                "name": f"{MOD}:{block}",
                "conditions": [{
                    "condition": "minecraft:block_state_property",
                    "block": f"{MOD}:{block}",
                    "properties": {"half": "lower", "part": "0"},
                }],
            }],
        }],
    }


def write_neoforge_data_maps():
    """Oxidation and waxing for NeoForge.

    Fabric wires this up with a registry call (OxidizableBlocksRegistry); NeoForge does it with
    data, through the built-in `neoforge:oxidizables` and `neoforge:waxables` data maps. The
    files live in the `neoforge` namespace, not ours, because NeoForge is what reads them.

    They are inert on Fabric: a file in a namespace Fabric does not know is simply ignored.
    That is why the same `common` serves both loaders with no conditionals.
    """
    root = os.path.join(ROOT, "common", "src", "main", "resources", "data", "neoforge",
                        "data_maps", "block")
    chain = oxidation_chain()
    oxidizables, waxables = {}, {}

    for width in (1, 2, 3, 4):
        for glass in (False, True):
            for i in range(len(chain) - 1):
                oxidizables[f"{MOD}:{block_name(chain[i], width, glass)}"] = {
                    "next_oxidation_stage": f"{MOD}:{block_name(chain[i + 1], width, glass)}"
                }
            for bare, waxed in waxable_pairs():
                waxables[f"{MOD}:{block_name(bare, width, glass)}"] = {
                    "waxed": f"{MOD}:{block_name(waxed, width, glass)}"
                }

    write_json(os.path.join(root, "oxidizables.json"), {"values": oxidizables})
    write_json(os.path.join(root, "waxables.json"), {"values": waxables})
    return 2


def write_recipes(material, craft):
    n = 0

    def recipe(name, body):
        nonlocal n
        write_json(os.path.join(DATA, "recipe", name + ".json"), body)
        n += 1

    one = block_name(material, 1, False)
    two = block_name(material, 2, False)

    # The door body, four at a time. With the ladder (w4 = 4 x w1) this makes a 4-wide door
    # cost exactly one batch. Copper's oxidised and waxed states are not crafted from scratch:
    # they come from time or from honeycomb.
    if craft is not None:
      recipe(one, {
        "type": "minecraft:crafting_shaped",
        "category": "building",
        "key": {"L": craft, "H": f"{MOD}:{HINGE}"},
        "pattern": ["LL ", "LLH", "LL "],
        "result": {"count": 4, "id": f"{MOD}:{one}"},
      })

    # The ladder: 2 and 3 from singles, 4 from two doubles. The 4-wide recipe mirrors the
    # mechanism -- §2.3 defines it as two 2-wide leaves.
    for width, part, pattern in ((2, one, ["DD"]), (3, one, ["DDD"]), (4, two, ["DD"])):
        name = block_name(material, width, False)
        recipe(name, {
            "type": "minecraft:crafting_shaped",
            "category": "building",
            "key": {"D": f"{MOD}:{part}"},
            "pattern": pattern,
            "result": {"count": 1, "id": f"{MOD}:{name}"},
        })

    # Glazing: an upgrade of the solid door of the same width, with as many glass blocks as
    # the door is wide. The door may sit at any position in the row -- the game has no "any
    # position" in a shaped recipe, so it is one recipe per position, grouped in the book.
    panes = {
        1: [["G", "D"]],
        2: [["GG", "D "], ["GG", " D"]],
        3: [["GGG", "D  "], ["GGG", " D "], ["GGG", "  D"]],
        4: [[" G ", "GDG", " G "]],
    }
    for width, patterns in panes.items():
        name = block_name(material, width, True)
        for slot, pattern in enumerate(patterns):
            recipe(name if slot == 0 else f"{name}_{slot}", {
                "type": "minecraft:crafting_shaped",
                "category": "building",
                "group": name,
                "key": {"D": f"{MOD}:{block_name(material, width, False)}",
                        "G": "minecraft:glass"},
                "pattern": pattern,
                "result": {"count": 1, "id": f"{MOD}:{name}"},
            })
    return n


if __name__ == "__main__":
    main()
