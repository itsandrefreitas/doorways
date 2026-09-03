"""Generates the Doorways textures, models, recipes and loot tables.

Writes 16x16 PNGs with plain zlib (no PIL). Blockstates and item definitions are NOT generated
here -- they come from datagen, because they derive from geometry. See DECISIONS.md, D-34.

Each material's colours are **sampled from the vanilla textures** (palettes.py), not invented.
Run it with the client jar within reach:

    python tools/gen_assets.py <project-root>
"""
import json
import math
import os
import struct
import sys
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from materials import (COPPER_IDS, GLASS_ID, IRON_ID, MATERIALS, SLIDING, STYLES, block_name,
                       display_name, model_stem, oxidation_chain, waxable_pairs)
from palettes import palette_from, read_png

MOD = "doorways"
HINGE = "iron_hinge"
TRACK = "sliding_track"
ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", MOD)
DATA = os.path.join(ROOT, "common", "src", "main", "resources", "data", MOD)
# Tags live under minecraft's own namespace: a datapack that names a vanilla tag adds to it.
VANILLA_DATA = os.path.join(ROOT, "common", "src", "main", "resources", "data", "minecraft")

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


def write_png_sized(path, px):
    """Writes an RGBA PNG of whatever size the pixel grid is."""
    height = len(px)
    width = len(px[0])
    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in px)

    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9))
                + chunk(b"IEND", b""))


def write_png(path, px):
    write_png_sized(path, px)


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


# A saloon leaf hangs clear of both floor and lintel. The lower half stops at row 11, which
# leaves the sill about a third of a block off the ground.
SALOON_SILL = 11

# Where the 3-thick leaf sits across its block, and why a spring door needs both.
#
# A blockstate rotates a model about the centre of its block, not about the hinge. A flush
# slice rotated 90 degrees lands flush against the next face, which is what makes every vanilla
# door look hinged. A centred slice rotated about the centre stays centred -- it becomes a bar
# lying across the middle of the block, at right angles to the doorway and attached to nothing.
#
# So the swung states keep the flush box, exactly like every other door, and only the closed
# state uses the centred one. The pivot appears to shift by a pixel and a half between the two,
# which nobody can see, and in exchange the leaf hangs in the middle of its frame where a saloon
# door belongs.
#
# CENTRED is duplicated in WideDoorBlock.CENTRED_LEAF_SHAPES, the collision side of this shape.
# Change one without the other and the door will look one way and stop you in another.
LEAF_FLUSH = (0, 3)
LEAF_CENTRED = (6.5, 9.5)

# The far track of a sliding door, immediately behind the near one. The panel that stays put
# runs on LEAF_FLUSH and the one that hides behind it runs here, which is why the two are
# visibly offset even with the door shut. WideDoorBlock.BACK_TRACK_SHAPES is the collision side.
LEAF_BACK = (3, 6)

# The arch peaks at row 2 in the middle of a column and falls to row 6 at its edges.
SALOON_CROWN = 2
SALOON_SPRING = 6


def saloon_head(x):
    """The row the panel top reaches, across one column.

    The arch is symmetric: it peaks at the middle and falls at both edges, so a leaf reads the
    same from either side. An arch that only rose toward one end looked straight from the
    opposite one, which is what the shape is supposed to avoid.
    """
    half_width = (W - 1) / 2
    t = abs(x - half_width) / half_width
    return SALOON_CROWN + round((SALOON_SPRING - SALOON_CROWN) * t ** 1.6)


def saloon_extent(half, x):
    """First and last panel row at this column position."""
    return (0, SALOON_SILL - 1) if half == "bottom" else (saloon_head(x), H - 1)


def saloon_leaf(pal, half, role):
    """Spindles under an arched rail on top, a solid panel below -- the western pattern.

    The gaps between the spindles are left transparent. A saloon door is something you see
    through, and the texture is the only place that can say so.
    """
    px = blank()
    for x in range(W):
        head, foot = saloon_extent(half, x)
        stile = x < 2 or x > W - 3

        for y in range(head, foot + 1):
            if stile:
                px[y][x] = pal["WOOD_LO"]
            elif half == "bottom":
                px[y][x] = pal["WOOD"]
            elif y <= head + 1 or y >= foot - 2:
                px[y][x] = pal["WOOD"]            # the rails that hold the spindles
            elif x % 3 == 0:
                # Daylight between the spindles. The phase leaves the column beside each stile
                # solid, which is what gives the leaf an end cap: the narrow faces sample the
                # three columns next to their own end, and a gap there would let you see
                # straight into the box from the side.
                px[y][x] = NONE
            else:
                px[y][x] = pal["WOOD_HI"]

        px[head][x] = pal["WOOD_HI"]
        px[foot][x] = pal["WOOD_LO"]

    if half == "bottom":
        # A raised field inside the frame, as on a panelled door.
        for y in range(3, SALOON_SILL - 3):
            for x in range(3, W - 3):
                px[y][x] = pal["WOOD_HI"] if (x + y) % 9 else pal["WOOD"]
        for x in range(3, W - 3):
            px[3][x] = pal["GROOVE"]
            px[SALOON_SILL - 4][x] = pal["GROOVE"]
        for y in range(3, SALOON_SILL - 3):
            px[y][3] = pal["GROOVE"]
            px[y][W - 4] = pal["GROOVE"]

    if role in ("left", "single"):
        saloon_strap(px, "left", half)
    if role in ("right", "single"):
        saloon_strap(px, "right", half)
    return px


def saloon_strap(px, side, half):
    """Ironwork on the hinge edge, following the arch rather than the block."""
    for x in ((0, 1) if side == "left" else (W - 2, W - 1)):
        head, foot = saloon_extent(half, x)
        for y in range(head, foot + 1):
            px[y][x] = IRON_HI if y % 5 == 0 else (IRON if x in (1, W - 2) else IRON_LO)


# A fusuma is a clear field in a lacquered border, and the field is the whole point -- it is
# what gets painted. No lattice: that is a shoji, and a lattice fights any picture put behind it.
FUSUMA_BORDER = 1

# The pull -- the hikite -- sits a little below the middle of the door, which lands it in the top
# rows of the lower half.
FUSUMA_PULL_X = 3
FUSUMA_PULL_Y = 3


def fusuma_leaf(pal, paper, half):
    """A plain paper panel in a thin lacquered border, with a round pull.

    The border runs down both edges of both halves and closes the outer end of each -- the top of
    the upper half, the bottom of the lower -- so the two stack into one framed panel with an
    unbroken field between them.
    """
    px = blank()
    for y in range(H):
        for x in range(W):
            px[y][x] = paper["WOOD_HI"]

    # A faint warmth across the field, so a whole wall of them is not a flat sheet of white.
    for y in range(H):
        for x in range(W):
            if (x * 5 + y * 3) % 17 == 0:
                px[y][x] = paper["WOOD"]

    for x in range(FUSUMA_BORDER):
        for y in range(H):
            px[y][x] = pal["GROOVE"]
            px[y][W - 1 - x] = pal["GROOVE"]
    edge = range(H - FUSUMA_BORDER, H) if half == "bottom" else range(FUSUMA_BORDER)
    for y in edge:
        for x in range(W):
            px[y][x] = pal["GROOVE"]

    if half == "bottom":
        fusuma_pull(px, pal)
    return px


def fusuma_pull(px, pal):
    """The hikite: a small recessed ring with a metal centre."""
    ring = ((0, 1), (1, 0), (1, 2), (2, 1))
    for dx, dy in ring:
        px[FUSUMA_PULL_Y + dy][FUSUMA_PULL_X + dx] = pal["GROOVE"]
    px[FUSUMA_PULL_Y + 1][FUSUMA_PULL_X + 1] = IRON_HI


def sliding_glass_leaf(half):
    """A sheet of glass in a slim iron frame.

    Iron rather than wood, for the reason the full-glass door already uses it: a fixed timber
    tone imposes itself on whatever it is built into, and grey does not. This door has no
    material of its own, so its frame has to be the one colour that sits quietly in any wall.
    """
    px = blank()
    for y in range(H):
        for x in range(W):
            px[y][x] = GLASS
    for y in range(2, 14):
        for x in range(1, 15):
            if (x + y) % 7 == 0:
                px[y][x] = GLASS_HI

    for y in range(H):
        px[y][0] = IRON_LO
        px[y][W - 1] = IRON_LO
        if y % 6 == 0:
            px[y][0] = IRON_HI
            px[y][W - 1] = IRON_HI
    edge = range(H - 2, H) if half == "bottom" else range(2)
    for y in edge:
        for x in range(W):
            px[y][x] = IRON if x % 5 else IRON_LO
    return px


def hidden_model(face_tex):
    """A panel that is elsewhere: no geometry at all.

    It keeps the particle texture even so. The block is still there and can still be broken, and
    without one the break particles would be the missing-texture chequer.
    """
    return {
        "ambientocclusion": False,
        "textures": {"particle": face_tex},
        "elements": [],
    }


def bookshelf_leaf(vanilla, role):
    """The vanilla bookshelf texture, unchanged.

    Redrawing the spines was the wrong idea: side by side with a real bookshelf the difference
    was obvious, and the point of this door is to match the block. Stacking the same texture on
    both halves is exactly what a wall of bookshelves looks like.
    """
    return [row[:] for row in vanilla]


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


def glass_leaf(half, role):
    """A pane in an iron frame, top to bottom."""
    px = blank()
    for y in range(H):
        for x in range(W):
            px[y][x] = GLASS
    for y in range(2, 14):
        for x in range(1, 15):
            if (x + y) % 7 == 0:
                px[y][x] = GLASS_HI

    if half == "bottom":
        band(px, 13, 15)
        band(px, 0, 1)
    else:
        band(px, 0, 2)
        band(px, 14, 15)
    if role in ("left", "single"):
        strap(px, "left")
    if role in ("right", "single"):
        strap(px, "right")
    return px


def item_texture(pal, vanilla, width, style):
    """The inventory sprite: a small elevation of the door, in its own style."""
    px = blank()
    total = min(width * 3 + 1, 13)
    x0 = (W - total) // 2
    # A saloon door is drawn shorter, so it reads as itself in a full hotbar.
    y0, y1 = (5, 12) if style == "saloon" else (2, 14)

    for y in range(y0, y1 + 1):
        for x in range(x0, x0 + total):
            if x in (x0, x0 + total - 1) or y in (y0, y1):
                px[y][x] = IRON_LO
            elif style == "full_glass":
                px[y][x] = GLASS_HI if (x + y) % 3 else GLASS
            elif style == "bookshelf":
                px[y][x] = vanilla[y % H][x % W]
            elif style == "glazed" and 4 <= y <= 7:
                px[y][x] = GLASS_HI
            elif (x - x0) % 3 == 0:
                px[y][x] = pal["GROOVE"]
            else:
                px[y][x] = pal["WOOD"] if y > 8 else pal["WOOD_HI"]

    middle = (y0 + y1) // 2
    for x in range(x0, x0 + total):
        px[middle][x] = IRON
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


def track_texture(pal):
    """A length of grooved rail, drawn on the diagonal so it reads as long rather than square.

    The counterpart to the hinge, and made of wood for the same reason the doors that use it
    are: a fusuma runs in a groove cut into the sill and the lintel, not on a steel rail.
    """
    px = blank()
    for i in range(2, 14):
        for d, tone in ((-1, "WOOD_HI"), (0, "WOOD"), (1, "WOOD"), (2, "WOOD_LO")):
            y = i + d
            if 0 <= y < H:
                px[y][i] = pal[tone]
        # The groove itself: a dark line down the middle of the board.
        px[i][i] = pal["GROOVE"]
    return px


# Door geometry does not belong in this file. Blockstates derive from DoorLayout and are
# generated by DoorwayBlockStateProvider, so that the rule has a single definition.
# See DECISIONS.md, D-34.
#
# Needing geometry here again would mean this script is generating the wrong thing: logic
# belongs in datagen, pixels belong here.


def leaf_texture(pal, vanilla, half, role, style):
    """The texture for one half of one column, in whichever style."""
    if style == "full_glass":
        return glass_leaf(half, role)
    if style == "saloon":
        return saloon_leaf(pal, half, role)
    if style == "bookshelf":
        return bookshelf_leaf(vanilla, role)
    return door_texture(pal, half, role, style == "glazed" and half == "top")


def mirror_faces(faces, mirrored):
    """Reverses the texture across the leaf, the way vanilla's *_open models do.

    Opening turns the leaf the opposite way about its hinge, which reverses which end of the
    texture points at the frame. Vanilla ships a second model per half for exactly this -- its
    door_bottom_left_open is door_bottom_left with the west and east UVs swapped. Without it the
    ironwork drawn on the hinge edge ends up at the free end of the leaf once it swings.

    Only the four upright faces are touched. The top and bottom are 3-pixel slivers where it
    could not be seen, and they carry a rotation that would send the mirror down the wrong axis.
    """
    if not mirrored:
        return faces
    out = {}
    for name, face in faces.items():
        if name in ("west", "east", "north", "south"):
            uv = list(face["uv"])
            uv[0], uv[2] = uv[2], uv[0]
            face = dict(face, uv=uv)
        out[name] = face
    return out


def panel_faces():
    """The six faces of a full-height leaf, with vanilla door_bottom_left's UVs."""
    return {
        "west": {"texture": "#face", "uv": [0, 0, 16, 16]},
        "east": {"texture": "#face", "uv": [16, 0, 0, 16]},
        "north": {"texture": "#face", "uv": [3, 0, 0, 16]},
        "south": {"texture": "#face", "uv": [0, 0, 3, 16]},
        "up": {"texture": "#face", "uv": [0, 0, 3, 16], "rotation": 270},
        "down": {"texture": "#face", "uv": [16, 13, 0, 16], "rotation": 90},
    }


def leaf_model(face_tex, mirrored=False):
    """Geometry copied from vanilla door_bottom_left: a 3/16 slice on the X axis, with the
    wide faces to west/east. Mirrored, it is vanilla's door_bottom_left_open."""
    return {
        "ambientocclusion": False,
        "textures": {"particle": face_tex, "face": face_tex},
        "elements": [{
            "from": [0, 0, 0], "to": [3, 16, 16],
            "faces": mirror_faces(panel_faces(), mirrored),
        }],
    }


def sliding_model(face_tex, spans):
    """One sliding panel, or a whole leaf stacked on both its tracks.

    Where two panels meet, neither draws the face between them -- they are coincident planes,
    and it is the same reason the vanilla door template omits the faces where its halves join.

    No mirroring here, and none needed: a sliding panel never turns, so there is no end of the
    texture that could come to face the wrong way.
    """
    elements = []
    for i, span in enumerate(spans):
        faces = panel_faces()
        if i > 0:
            del faces["west"]
        if i < len(spans) - 1:
            del faces["east"]
        elements.append({"from": [span[0], 0, 0], "to": [span[1], 16, 16], "faces": faces})
    return {
        "ambientocclusion": False,
        "textures": {"particle": face_tex, "face": face_tex},
        "elements": elements,
    }


def leaf_box(z0, z1, y0, y1, faces, span, mirrored):
    """One box of a leaf, with the UV conventions taken from the vanilla door template.

    The texture's 16-wide axis runs along z, and v counts down from the top of the block, so a
    box between y0 and y1 shows texture rows 16-y1 to 16-y0. For a full-height box these
    formulas reproduce block/door_bottom_left exactly.

    `span` is where the 3-thick slice sits along x -- LEAF_FLUSH or LEAF_CENTRED. The UVs do not
    depend on it: the wide faces do not care where the slice sits, and the narrow ones are 3
    across either way.

    The narrow end faces sample the three columns beside their <b>own</b> end, rather than the
    three at the texture's edge that the vanilla template uses. Vanilla can use the edge because
    its box always spans the full width, so the edge is the end. An arch step does not: it stops
    partway, and the texture's edge columns are transparent at the rows an arch step covers --
    the arch is low there. Sampling them leaves the step with no end cap, and the door reads as
    hollow the moment you look at it from the side.
    """
    v0, v1 = H - y1, H - y0
    out = {}
    if "west" in faces:
        out["west"] = {"texture": "#face", "uv": [z0, v0, z1, v1]}
    if "east" in faces:
        out["east"] = {"texture": "#face", "uv": [z1, v0, z0, v1]}
    if "north" in faces:
        out["north"] = {"texture": "#face", "uv": [z0 + 3, v0, z0, v1]}
    if "south" in faces:
        out["south"] = {"texture": "#face", "uv": [z1 - 3, v0, z1, v1]}
    if "up" in faces:
        out["up"] = {"texture": "#face", "uv": [z0, v0 + 3, z1, v0], "rotation": 90}
    if "down" in faces:
        out["down"] = {"texture": "#face", "uv": [z1, v1 - 3, z0, v1], "rotation": 90}
    return {"from": [span[0], y0, z0], "to": [span[1], y1, z1],
            "faces": mirror_faces(out, mirrored)}


def saloon_leaf_model(face_tex, half, span, mirrored=False):
    """A leaf cut to the panel, with the arch built as steps.

    A box has a flat top, so a single one cannot follow an arch: set it at the highest point and
    a bar hangs over the low parts, set it at the lowest and the arch is clipped. Dropping the
    top face hides the bar but leaves the door invisible from above.

    So the arch is stacked instead: a base box up to the lowest point of the arch, then one
    layer per step above it. Each layer is shorter along z than the one below, which is what
    keeps their vertical faces from landing on top of each other and flickering.

    Top and bottom halves meet at y=16/y=0, so neither draws a face there -- the same reason
    the vanilla door template omits them.
    """
    sides = ("north", "south", "west", "east")

    if half == "bottom":
        elements = [leaf_box(0, W, H - SALOON_SILL, H, sides + ("down",), span, mirrored)]
    else:
        heads = [saloon_head(x) for x in range(W)]
        lowest = max(heads)
        elements = [leaf_box(0, W, 0, H - lowest, sides + ("up",), span, mirrored)]

        previous = lowest
        for head in sorted({h for h in heads if h < lowest}, reverse=True):
            covered = [x for x in range(W) if heads[x] <= head]
            elements.append(leaf_box(min(covered), max(covered) + 1,
                                     H - previous, H - head, sides + ("up",), span, mirrored))
            previous = head

    return {
        "ambientocclusion": False,
        "textures": {"particle": face_tex, "face": face_tex},
        "elements": elements,
    }


def mod_icon(pal):
    """The mod's icon: a two-column door with glass above, drawn once and scaled up.

    Drawn at 16x16 like everything else and enlarged with whole pixels, so it stays sharp and
    keeps the same look as the doors themselves rather than being separate artwork.
    """
    px = blank()
    left, right = 1, W - 2

    for y in range(1, H - 1):
        for x in range(left, right + 1):
            if y in (1, H - 2) or x in (left, right):
                px[y][x] = IRON_LO
            elif 4 <= y <= 8:
                px[y][x] = GLASS_HI if (x + y) % 3 else GLASS
            elif x == (left + right) // 2:
                px[y][x] = pal["GROOVE"]           # the seam between the two leaves
            else:
                px[y][x] = pal["WOOD"] if y > 9 else pal["WOOD_HI"]

    for x in range(left, right + 1):
        px[3][x] = IRON                            # the rail above the glass
        px[9][x] = IRON
    for y in (2, 6, 12):
        px[y][left] = IRON_HI
        px[y][right] = IRON_HI
    return px


def scale(px, factor):
    """Whole-pixel enlargement. No blending: the result has to stay pixel art."""
    return [[px[y // factor][x // factor] for x in range(W * factor)]
            for y in range(H * factor)]


# ---------------------------------------------------------------- writing
def main():
    jar = zipfile.ZipFile(CLIENT_JAR)
    lang = {"itemGroup.doorways": "Doorways",
            f"item.{MOD}.{HINGE}": "Iron Hinge",
            f"item.{MOD}.{TRACK}": "Sliding Track"}
    n = 0

    palettes, faces = {}, {}
    for material, label, texture, craft in MATERIALS:
        data = jar.read(f"assets/minecraft/textures/block/{texture}.png")
        palettes[material] = palette_from(data)
        # Kept whole for the styles that copy the vanilla face rather than redraw it.
        _, _, flat = read_png(data)
        faces[material] = [flat[row * W:(row + 1) * W] for row in range(H)]

    # A shoji's panel is paper, and the rule here is that colours are sampled rather than chosen
    # (D-02). Vanilla has no paper block, so the item texture is what there is to sample.
    paper = palette_from(jar.read("assets/minecraft/textures/item/paper.png"))

    # A glazed door's lower half is the solid model, so the same stem comes up twice. Written
    # once, on whichever style reaches it first.
    written = set()

    for style, (materials, widths) in STYLES.items():
        for material, label, texture, craft in materials:
            pal = palettes[material]
            vanilla = faces[material]

            for half in ("bottom", "top"):
                # A sliding panel is framed all round and self-contained, so the left/mid/right
                # roles collapse into one. What its four models say instead is which track the
                # panel is on and whether it is parked -- there is no swung twin.
                if style in SLIDING:
                    stem = model_stem(material, style, half, "panel")
                    # The glass shoji has no wood of its own -- its material is the glass in the
                    # panel -- but its frame is wooden, and its recipe takes any planks. Oak is
                    # what "any planks" looks like; letting it inherit the glass palette gave it
                    # a blue frame, which the recipe flatly contradicts.
                    glass = style == "sliding_glass"
                    write_png(os.path.join(ASSETS, "textures", "block", stem + ".png"),
                              sliding_glass_leaf(half) if glass
                              else fusuma_leaf(pal, paper, half))
                    face = f"{MOD}:block/{stem}"
                    n += 1
                    # Four models. A door at rest is an ordinary block and draws itself from
                    # one of these; only while a panel is actually travelling does the renderer
                    # take over. That is what keeps a sliding door visible past 64 blocks, which
                    # is as far as a block entity renderer reaches.
                    for role, model in (
                            ("front", sliding_model(face, (LEAF_FLUSH,))),
                            ("back", sliding_model(face, (LEAF_BACK,))),
                            ("stacked", sliding_model(face, (LEAF_FLUSH, LEAF_BACK))),
                            ("hidden", hidden_model(face))):
                        write_json(os.path.join(ASSETS, "models", "block",
                                                model_stem(material, style, half, role) + ".json"),
                                   model)
                        n += 1
                    continue

                for role in ("single", "left", "mid", "right"):
                    stem = model_stem(material, style, half, role)
                    if stem in written:
                        continue
                    written.add(stem)
                    write_png(os.path.join(ASSETS, "textures", "block", stem + ".png"),
                              leaf_texture(pal, vanilla, half, role, style))
                    face = f"{MOD}:block/{stem}"

                    # Two models per stem, sharing the texture: in the frame, and swung out of
                    # it. A saloon door moves its box as well, hanging centred while closed.
                    if style == "saloon":
                        shut = saloon_leaf_model(face, half, LEAF_CENTRED)
                        swung = saloon_leaf_model(face, half, LEAF_FLUSH, mirrored=True)
                    else:
                        shut = leaf_model(face)
                        swung = leaf_model(face, mirrored=True)

                    write_json(os.path.join(ASSETS, "models", "block", stem + ".json"), shut)
                    write_json(os.path.join(ASSETS, "models", "block",
                                            model_stem(material, style, half, role, swung=True)
                                            + ".json"), swung)
                    n += 3

            for width in widths:
                block = block_name(material, width, style)
                lang[f"block.{MOD}.{block}"] = display_name(label, width, style)

                # Blockstates and item definitions come from datagen, not from here:
                # they derive from geometry. See DECISIONS.md, D-34.
                write_png(os.path.join(ASSETS, "textures", "item", block + ".png"),
                          item_texture(pal, vanilla, width, style))
                write_json(os.path.join(ASSETS, "models", "item", block + ".json"),
                           {"parent": "item/generated",
                            "textures": {"layer0": f"{MOD}:item/{block}"}})
                write_json(os.path.join(DATA, "loot_table", "blocks", block + ".json"),
                           loot_table(block, width))
                n += 3

            n += write_recipes(material, craft, style, widths)

    # The icon the launcher and the in-game mod list show. 128x128 is what Modrinth asks for.
    icon = scale(mod_icon(palettes["oak"]), 8)
    write_png_sized(os.path.join(ASSETS, "icon.png"), icon)
    n += 1

    n += write_mineable_tags()

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
    n += 4

    # the sliding track: what a hinge is to the doors that swing
    write_png(os.path.join(ASSETS, "textures", "item", TRACK + ".png"),
              track_texture(palettes["oak"]))
    write_json(os.path.join(ASSETS, "models", "item", TRACK + ".json"),
               {"parent": "item/generated", "textures": {"layer0": f"{MOD}:item/{TRACK}"}})
    write_json(os.path.join(ASSETS, "items", TRACK + ".json"),
               {"model": {"type": "minecraft:model", "model": f"{MOD}:item/{TRACK}"}})
    # "L s L" is the one row of planks and sticks vanilla leaves free: LLL is a slab, LL is a
    # pressure plate, and LsL only exists doubled, as a fence.
    write_json(os.path.join(DATA, "recipe", TRACK + ".json"), {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "key": {"L": "#minecraft:planks", "s": "minecraft:stick"},
        "pattern": ["LsL"],
        "result": {"count": 4, "id": f"{MOD}:{TRACK}"},
    })
    # Waxing at the bench, as well as honeycomb in hand -- vanilla offers both routes.
    for bare, waxed in waxable_pairs():
        for width in (1, 2, 3, 4):
            for style in ("solid", "glazed"):
                src, dst = block_name(bare, width, style), block_name(waxed, width, style)
                write_json(os.path.join(DATA, "recipe", dst + "_from_honeycomb.json"), {
                    "type": "minecraft:crafting_shapeless",
                    "category": "building",
                    "group": dst,
                    "ingredients": [f"{MOD}:{src}", "minecraft:honeycomb"],
                    "result": {"id": f"{MOD}:{dst}"},
                })
                n += 1

    n += write_paintings(lang)
    n += write_neoforge_data_maps()
    write_json(os.path.join(ASSETS, "lang", "en_us.json"), dict(sorted(lang.items())))
    n += 5

    doors = sum(len(m) * len(w) for m, w in STYLES.values())
    print(f"{n} files, {len(MATERIALS)} materials, {len(STYLES)} styles, {doors} doors")


# ------------------------------------------------------- paintings for the fusuma
#
# A fusuma is the wall of the room, and it has been painted for as long as fusuma have existed.
# The canvas is the whole door: two panels across or four, always two blocks tall, so 32x32 or
# 64x32 pixels. Each block draws its own slice and the slices meet.
#
# Nothing here is sampled. D-02's rule -- colours come from the vanilla textures -- is about
# matching materials the player already knows; a painting matches nothing, and takes its palette
# from what these were actually painted with: one stick of sumi ink ground to four strengths,
# two greens, and a single red for the seal.
PAINTING_HEIGHT = 32

# Sumi ink in four values, and one accent. A fusuma was painted with a single black stick ground
# to different strengths, so most of a picture is one colour used four ways.
INK = (26, 24, 22, 255)
INK_MID = (62, 58, 54, 240)
INK_SOFT = (104, 100, 92, 210)
INK_WASH = (150, 146, 136, 120)
PINE_DARK = (38, 58, 42, 255)
PINE_MID = (64, 92, 62, 245)
PINE_LIGHT = (96, 124, 84, 225)
SEAL = (158, 46, 38, 245)


# Colours the pine did not need. Each is one family in three values, because that is what a
# picture painted with a few pigments looks like.
BAMBOO = (86, 122, 62, 255)
BAMBOO_MID = (112, 148, 78, 245)
BAMBOO_PALE = (146, 174, 112, 220)
BLOSSOM = (222, 158, 178, 245)
BLOSSOM_PALE = (240, 202, 214, 235)
BLOSSOM_HEART = (198, 108, 134, 250)
AUTUMN = (186, 92, 44, 250)
AUTUMN_DEEP = (150, 58, 34, 250)
AUTUMN_PALE = (214, 146, 74, 235)
WATER = (72, 104, 138, 245)
WATER_DEEP = (44, 70, 102, 250)
WATER_PALE = (146, 176, 200, 220)
FOAM = (238, 242, 246, 245)
SNOW = (240, 242, 244, 240)
CRANE = (246, 246, 242, 250)
MOONLIGHT = (246, 226, 158, 240)
MOON_HALO = (228, 200, 132, 150)
NIGHT = (78, 92, 128, 235)
NIGHT_DEEP = (52, 62, 96, 245)


def canvas(width_blocks):
    return [[NONE] * (16 * width_blocks) for _ in range(PAINTING_HEIGHT)]


def put(px, x, y, colour):
    if 0 <= y < len(px) and 0 <= x < len(px[0]):
        px[y][x] = colour


def blot(px, x, y, radius, colour):
    """A round dab, which is what the end of a brush leaves."""
    r = int(math.ceil(radius))
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx * dx + dy * dy <= radius * radius:
                put(px, x + dx, y + dy, colour)


def stroke(px, points, start_width, end_width, colour):
    """A brush stroke through a series of points, thinning as it goes.

    Tapering is most of what makes a stroke look painted rather than drawn: a branch that is
    three pixels wide at the trunk and one at the tip reads as a branch, and one that is two
    pixels wide throughout reads as a wire.
    """
    total = 0.0
    for i in range(len(points) - 1):
        total += math.dist(points[i], points[i + 1])
    walked = 0.0
    for i in range(len(points) - 1):
        (x0, y0), (x1, y1) = points[i], points[i + 1]
        steps = max(int(math.dist((x0, y0), (x1, y1)) * 2), 1)
        for s in range(steps + 1):
            t = s / steps
            x = x0 + (x1 - x0) * t
            y = y0 + (y1 - y0) * t
            along = (walked + math.dist((x0, y0), (x1, y1)) * t) / max(total, 0.001)
            blot(px, round(x), round(y),
                 start_width + (end_width - start_width) * along, colour)
        walked += math.dist((x0, y0), (x1, y1))


def needle_fan(px, cx, cy, length, spread, tilt, density=22):
    """A cluster of pine needles: short strokes radiating from one point.

    Drawn as strokes rather than as a blob because that is what pine reads as at any size -- the
    silhouette of a pine cluster is spiky, and a smooth ellipse reads as a bush.
    """
    for i in range(density):
        angle = tilt + spread * (i / (density - 1) - 0.5)
        far = length * (0.80 + 0.20 * ((i * 7) % 5) / 4.0)
        x1 = cx + math.cos(angle) * far
        y1 = cy - math.sin(angle) * far
        shade = PINE_DARK if i % 3 else PINE_MID
        stroke(px, [(cx, cy), (x1, y1)], 0.7, 0.3, shade)
    # a few lighter needles on top, so the mass has a lit side
    for i in range(density // 3):
        angle = tilt + spread * ((i + 0.5) / (density // 3) - 0.5) * 0.8
        x1 = cx + math.cos(angle) * length * 0.6
        y1 = cy - math.sin(angle) * length * 0.6
        stroke(px, [(cx, cy), (x1, y1)], 0.6, 0.3, PINE_LIGHT)


def wash(px, cx, cy, rx, ry):
    """The solid heart of a foliage mass; the fan of needles breaks up its edge."""
    for y in range(cy - ry, cy + ry + 1):
        for x in range(cx - rx, cx + rx + 1):
            nx = (x - cx) / max(rx, 1)
            ny = (y - cy) / max(ry, 1)
            if nx * nx + ny * ny <= 1.0:
                if 0 <= y < len(px) and 0 <= x < len(px[0]) and px[y][x] == NONE:
                    px[y][x] = PINE_DARK


def seal_mark(px, x, y):
    """The painter's seal. Two pixels of red in a picture of black is the whole accent."""
    for dy in range(5):
        for dx in range(4):
            put(px, x + dx, y + dy, SEAL)
    put(px, x + 1, y + 1, NONE)
    put(px, x + 2, y + 2, NONE)
    put(px, x + 1, y + 3, NONE)


def lesser_pine(px, base_x, base_y, height, ink, tiers):
    """A whole pine, smaller and simpler, for the ones standing further away.

    Not a copy of the big one: a distant tree is read by its silhouette, so it gets a trunk,
    a branch to each mass, and no bark and no ground. Trying to draw the same tree smaller is
    what makes a background look like a foreground out of focus.
    """
    top = base_y - height
    trunk = [(base_x, base_y), (base_x + 1, base_y - height * 0.55), (base_x + 2, top)]
    stroke(px, trunk, 0.9, 0.35, ink)
    for i, (dx, dy, length, tilt) in enumerate(tiers):
        cx, cy = base_x + dx, base_y - height + dy
        # Every mass hangs off a branch. Without them the tiers read as three separate bushes
        # standing in a row, which is exactly how it looked the first time.
        along = (base_y - cy) / max(height, 1)
        trunk_x = base_x + 2 * (1.0 - along)
        stroke(px, [(trunk_x, cy + 1), (cx, cy)], 0.7, 0.3, ink)
        wash(px, cx, cy, max(int(length * 0.6), 2), 2)
        needle_fan(px, cx, cy, length, 1.5, tilt, density=11 - i * 2)


def moon(px, cx, cy, radius):
    """The moon: paper left unpainted, with a ring drawn round it and a halo beyond.

    Painted the way it is on a real screen -- the disc is the paper itself and the brush goes
    round it, so the brightest thing in the picture costs no pigment at all.

    Two rings rather than one. A single thin ring read as a drawn circle; a firm inner edge with
    a broken halo outside it reads as light.
    """
    for y in range(cy - radius - 3, cy + radius + 4):
        for x in range(cx - radius - 3, cx + radius + 4):
            d = math.dist((x, y), (cx, cy))
            if radius - 0.5 <= d <= radius + 0.6:
                put(px, x, y, INK_SOFT)
            elif radius + 0.6 < d <= radius + 1.7:
                put(px, x, y, INK_WASH)


def rock(px, cx, cy, half_width, height):
    """A rock at the foot of the tree: a flat-topped mass, dark, with one lit edge."""
    # Solid, with one lit edge and a couple of cracks. Filling it with a diagonal pattern was
    # the first attempt and read as hatching applied by a machine.
    for y in range(cy - height, cy + 1):
        span = int(half_width * (1.0 - 0.45 * (cy - y) / max(height, 1)))
        for x in range(cx - span, cx + span + 1):
            put(px, x, y, INK_MID)
    for x in range(cx - half_width + 1, cx + half_width - 1):
        put(px, x, cy - height, INK_SOFT)
    for k in range(1, height):
        put(px, cx - 1 + (k % 2), cy - k, INK)


def far_hill(px, cx, cy, half_width, height):
    """A hill on the horizon, in the palest ink there is. A shape, not a haze.

    Mist was tried twice and read as dirt both times; a silhouette carries distance at this size
    where a gradient cannot.
    """
    for y in range(cy - height, cy + 1):
        # Wide and low, and rounded rather than pointed: a ridge seen from far away is almost
        # all horizon. Drawn tall it stops being distance and becomes a boulder, which is what
        # the first attempt looked like.
        span = int(half_width * (1.0 - (cy - y) / max(height + 1, 1)) ** 0.62)
        for x in range(cx - span, cx + span + 1):
            if 0 <= x < len(px[0]) and px[y][x] == NONE:
                put(px, x, y, INK_WASH)


def cluster_mass(px, cx, cy, radius, colour, edge_colour):
    """A round mass with a broken edge. The break comes from the coordinates rather than from
    randomness, so the same picture is drawn the same way every time."""
    r = int(math.ceil(radius))
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            d = math.hypot(dx, dy)
            if d > radius:
                continue
            edge = d > radius - 1.2
            if edge and (dx * 3 + dy * 5) % 4 == 0:
                continue
            put(px, cx + dx, cy + dy, edge_colour if edge else colour)


def pine(width_blocks):
    """An evergreen, drawn the way the maple is drawn.

    It began as a tiered pine -- horizontal shelves of needles on long branches -- which is the
    truer pine and the worse picture: at this size the shelves read as steps. The maple's shape
    won on looking at the two side by side, so this is that tree in evergreen colours, with a
    moon behind it.

    On four panels the tree does not stretch. The trunk moves to the second panel, the crown
    opens both ways, and a smaller tree stands further off.
    """
    px = canvas(width_blocks)
    w = 16 * width_blocks
    wide = width_blocks >= 4
    # The same footing as the maple: out of the left corner, leaning into the paper. It stood in
    # the middle of a wide door for a while, with a branch reaching back over the first panel to
    # balance it, and the maple's placing simply reads better.
    base = 6 if wide else 4

    # everything behind the tree first
    moon(px, w - 9 if wide else 25, 7, 5 if wide else 4)
    if wide:
        far_hill(px, w - 24, 29, 19, 5)
        far_hill(px, w - 7, 29, 12, 4)

    # the trunk, out of the ground and leaning
    stroke(px, [(base, 31), (base + 2, 24), (base + 5, 18), (base + 7, 12)], 1.2, 0.4, INK)

    limbs = [([(base + 2, 24), (base + 8, 22), (base + 12, 23)], 0.8),
             ([(base + 5, 18), (base + 10, 15), (base + 14, 16)], 0.7),
             ([(base + 7, 12), (base + 11, 9), (base + 14, 10)], 0.6)]
    for points, thickness in limbs:
        stroke(px, points, thickness, 0.3, INK)

    masses = [(base + 13, 22, 3.6, PINE_DARK, PINE_MID),
              (base + 15, 15, 3.2, PINE_DARK, PINE_LIGHT),
              (base + 15, 9, 2.8, PINE_MID, PINE_LIGHT)]
    # Three overlapping lobes each, not one disc: a round mass of foliage reads as fruit.
    for cx, cy, r, colour, edge in masses:
        for dx, dy, k in ((0, 0, 1.0), (-r * 0.8, r * 0.4, 0.7), (r * 0.7, r * 0.5, 0.6)):
            cluster_mass(px, round(cx + dx), round(cy + dy), r * k, colour, edge)

    rock(px, base + 8 if wide else base + 7, 31, 4, 3)
    for x in range(max(base - 3, 1), min(base + 18, w - 2)):
        if (x * 5) % 7 > 4:
            put(px, x, 30, INK_SOFT)

    if wide:
        lesser_pine(px, w - 12, 31, 12, INK_SOFT,
                    ((-3, 4, 3.5, 0.15), (2, 0, 3.0, 0.35)))
        for bx, by in ((base + 26, 7), (base + 32, 4)):
            put(px, bx, by, INK_MID)
            put(px, bx - 1, by - 1, INK_MID)
            put(px, bx + 1, by - 1, INK_MID)

    seal_mark(px, w - 6, 26)
    return px


def leaf(px, x0, y0, x1, y1, colour, width=1.0):
    """A single leaf: one stroke that swells in the middle and comes to a point."""
    steps = max(int(math.dist((x0, y0), (x1, y1)) * 2), 1)
    for s in range(steps + 1):
        t = s / steps
        swell = math.sin(t * math.pi) * width
        blot(px, round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), 0.4 + swell, colour)


def cluster(px, cx, cy, radius, colour, edge_colour, heart=None):
    """A round mass with a broken edge -- blossom, foliage, foam.

    The break comes from the coordinates rather than from randomness, so the same picture is
    drawn the same way every time and the generator stays reproducible.
    """
    r = int(math.ceil(radius))
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            d = math.hypot(dx, dy)
            if d > radius:
                continue
            edge = d > radius - 1.2
            if edge and (dx * 3 + dy * 5) % 4 == 0:
                continue
            put(px, cx + dx, cy + dy, edge_colour if edge else colour)
    if heart is not None:
        put(px, cx, cy, heart)


# ------------------------------------------------------------------ bamboo
def bamboo(width_blocks):
    """A few culms, a rock, and birds.

    The first version filled the paper with bamboo and nothing else, and a thicket is not a
    picture: with every stalk the same and no space between them there is nothing to look at.
    Fewer culms, further apart, at three depths -- and something that is not bamboo to look at
    them.
    """
    px = canvas(width_blocks)
    w = 16 * width_blocks

    # near, middle, far: each paler and thinner than the one in front
    culms = [(7, 31, 4, BAMBOO, 1.0), (12, 31, 11, BAMBOO_MID, 0.8), (3, 31, 15, BAMBOO_PALE, 0.6)]
    if width_blocks >= 4:
        culms += [(26, 31, 6, BAMBOO, 1.0), (33, 31, 13, BAMBOO_PALE, 0.6),
                  (w - 12, 31, 9, BAMBOO_MID, 0.8)]
    for x, base_y, top, colour, thickness in culms:
        stroke(px, [(x, base_y), (x + 1, (base_y + top) // 2), (x + 1, top)],
               0.9 * thickness, 0.6 * thickness, colour)
        # nodes: the joints, which are what makes a green line read as bamboo
        for y in range(top + 4, base_y, 7):
            put(px, x, y, INK_MID)
            put(px, x + 1, y, INK_MID)
        # leaves, in threes, and only on two joints: a leaf at every node is a hedge
        for i, y in enumerate(range(top + 3, base_y - 10, 9)):
            side = 1 if (i + x) % 2 else -1
            for k in range(3):
                leaf(px, x + 1, y, x + 1 + side * (4 + k), y - 3 + k * 2, colour, 0.5)

    rock(px, 19 if width_blocks < 4 else 20, 31, 5, 3)
    for x in range(2, (18 if width_blocks < 4 else w - 12)):
        if (x * 5) % 7 > 4:
            put(px, x, 30, INK_SOFT)

    birds = ((24, 7), (28, 4)) if width_blocks < 4 else ((44, 8), (49, 5), (54, 10))
    for bx, by in birds:
        put(px, bx, by, INK_MID)
        put(px, bx - 1, by - 1, INK_MID)
        put(px, bx + 1, by - 1, INK_MID)
    if width_blocks >= 4:
        far_hill(px, w - 8, 30, 10, 5)
    seal_mark(px, w - 6, 26)
    return px


# ------------------------------------------------------------------ cherry
def cherry(width_blocks):
    """A branch of blossom, and petals already falling. Pink is the only pigment."""
    px = canvas(width_blocks)
    w = 16 * width_blocks
    # Thin and curved. A thick straight branch across the top reads as a beam with flowers
    # glued to it, which is what the first attempt looked like.
    branch = ([(0, 3), (8, 8), (17, 6), (26, 11), (36, 8), (46, 12), (w - 3, 9)]
              if width_blocks >= 4 else [(0, 3), (7, 8), (14, 7), (19, 11)])
    stroke(px, branch, 0.9, 0.35, INK)
    spurs = [((6, 6), (5, 12)), ((12, 7), (14, 13)), ((17, 7), (19, 3))]
    if width_blocks >= 4:
        spurs += [((21, 9), (23, 15)), ((30, 9), (33, 15)), ((40, 9), (43, 4)),
                  ((46, 12), (49, 18)), ((52, 11), (55, 6)), ((w - 8, 10), (w - 5, 16))]
    for a, b in spurs:
        stroke(px, [a, b], 0.7, 0.3, INK_MID)
    hearts = [(5, 13), (14, 14), (19, 2), (9, 8), (17, 7)]
    if width_blocks >= 4:
        hearts += [(23, 16), (33, 16), (36, 8), (43, 3), (49, 19), (55, 5), (w - 5, 17),
                   (w - 10, 10)]
    for i, (cx, cy) in enumerate(hearts):
        cluster(px, cx, cy, 3.0 if i % 2 else 2.4, BLOSSOM, BLOSSOM_PALE, BLOSSOM_HEART)
    # petals on their way down: three pixels, well apart
    petals = [(7, 20), (13, 25), (20, 22)] if width_blocks < 4 else [
        (7, 20), (13, 25), (20, 22), (28, 24), (36, 20), (44, 27)]
    for cx, cy in petals:
        put(px, cx, cy, BLOSSOM)
        put(px, cx + 1, cy + 1, BLOSSOM_PALE)
    seal_mark(px, w - 6, 26)
    return px


# ------------------------------------------------------------------ autumn
def autumn(width_blocks):
    """Maple in autumn: a branch from the low corner, and leaves gone over to red."""
    px = canvas(width_blocks)
    w = 16 * width_blocks
    base = 4 if width_blocks < 4 else 6
    # behind the tree, as in the pine: the same moon, and the same reason for it
    moon(px, w - 8, 7, 5 if width_blocks >= 4 else 4)
    stroke(px, [(base, 31), (base + 2, 24), (base + 5, 18), (base + 7, 12)], 1.2, 0.4, INK)
    limbs = [([(base + 2, 24), (base + 8, 22), (base + 12, 23)], 0.8),
             ([(base + 5, 18), (base + 10, 15), (base + 14, 16)], 0.7),
             ([(base + 7, 12), (base + 11, 9), (base + 14, 10)], 0.6)]
    if width_blocks >= 4:
        limbs.append(([(base + 6, 16), (base + 14, 13), (base + 21, 15)], 0.7))
    for points, thickness in limbs:
        stroke(px, points, thickness, 0.3, INK)
    masses = [(base + 13, 22, 3.4, AUTUMN, AUTUMN_PALE),
              (base + 15, 15, 3.0, AUTUMN_DEEP, AUTUMN),
              (base + 15, 9, 2.6, AUTUMN, AUTUMN_PALE)]
    if width_blocks >= 4:
        masses += [(base + 22, 14, 3.2, AUTUMN_DEEP, AUTUMN),
                   (base + 18, 19, 2.4, AUTUMN, AUTUMN_PALE)]
    # Three overlapping lobes each, not one disc: a round mass of leaves reads as fruit.
    for cx, cy, r, colour, edge in masses:
        for dx, dy, k in ((0, 0, 1.0), (-r * 0.8, r * 0.4, 0.7), (r * 0.7, r * 0.5, 0.6)):
            cluster(px, round(cx + dx), round(cy + dy), r * k, colour, edge)
    fallen = [(base + 4, 28), (base + 9, 29), (base + 17, 27)]
    if width_blocks >= 4:
        # a second, younger maple against the far edge, and leaves blown the length of the floor
        stroke(px, [(w - 11, 31), (w - 10, 25), (w - 8, 20)], 0.9, 0.35, INK_MID)
        stroke(px, [(w - 10, 25), (w - 14, 23)], 0.6, 0.3, INK_MID)
        for cx, cy, r in ((w - 7, 19, 2.6), (w - 14, 22, 2.0), (w - 11, 15, 2.2)):
            cluster(px, cx, cy, r, AUTUMN, AUTUMN_PALE)
        fallen += [(w - 20, 28), (w - 26, 29), (w - 32, 27), (w - 38, 29)]
    for cx, cy in fallen:
        put(px, cx, cy, AUTUMN_PALE)
        put(px, cx + 1, cy, AUTUMN)
    for x in range(1, w - 2):
        if (x * 5) % 7 > 4:
            put(px, x, 30, INK_SOFT)
    seal_mark(px, w - 6, 26)
    return px


# ------------------------------------------------------------------ wave
def wave(width_blocks):
    """A wave at the moment it curls: a long back, a lip that overhangs, and foam falling off it.

    The first attempt drew the body as a stack of rising strokes and came out as an arrowhead.
    A wave is read from its **overhang** -- the crest leaning further out than the water beneath
    it -- so that is what is drawn first and everything else hangs off it.
    """
    px = canvas(width_blocks)
    w = 16 * width_blocks
    crest_x = 13 if width_blocks < 4 else 22

    # The back of the wave: three lines following the same rise, with paper between them. Filled
    # solid it stopped being water and became an iceberg -- what says "water" here is that you
    # can see through it.
    back = [(w - 2, 27), (crest_x + 13, 25), (crest_x + 6, 20), (crest_x, 13)]
    for depth, colour in ((0, WATER_DEEP), (3, WATER), (6, WATER_PALE)):
        stroke(px, [(x, y + depth) for x, y in back], 1.0, 0.7, colour)

    # the lip: over the top and back on itself, which is the whole gesture
    stroke(px, [(crest_x, 13), (crest_x - 5, 11), (crest_x - 9, 15)], 1.2, 0.7, WATER_DEEP)
    cluster(px, crest_x - 4, 11, 3.0, FOAM, WATER_PALE)
    cluster(px, crest_x - 9, 15, 2.2, FOAM, WATER_PALE)

    # foam falling from under the lip
    for i, (dx, length) in enumerate(((-8, 5), (-5, 7), (-2, 4), (1, 6))):
        stroke(px, [(crest_x + dx, 16), (crest_x + dx - 1, 16 + length)], 0.8, 0.4,
               FOAM if i % 2 else WATER_PALE)

    # the trough in front, in lines that flatten with distance
    for i, y in enumerate(range(23, 31, 2)):
        stroke(px, [(1, y + 1), (w // 3, y), (w - 2, y + 1)], 0.7, 0.7,
               WATER if i % 2 else WATER_PALE)

    if width_blocks >= 4:
        stroke(px, [(w - 26, 21), (w - 20, 19), (w - 14, 21)], 0.9, 0.5, WATER)
        cluster(px, w - 20, 18, 2.2, FOAM, WATER_PALE)
        far_hill(px, w - 9, 19, 8, 4)
    seal_mark(px, w - 6, 26)
    return px


# ------------------------------------------------------------------ waterfall
def waterfall(width_blocks):
    """A fall between two cliffs, with the water drawn by what is left unpainted.

    Three attempts. A filled rectangle of stripes read as a barcode; round masses of ink read as
    bushes. Rock is **angular** -- that is the whole difference between stone and foliage -- so
    it is drawn as stacked slabs with straight edges and a lit top, and the water is a widening
    sheet with a dark lip at the top and a splash where it lands.
    """
    px = canvas(width_blocks)
    w = 16 * width_blocks
    fall_x = 12 if width_blocks < 4 else 20

    def cliff(x_outer, x_inner, top, bottom):
        """One side of the gorge: slabs, each stepping in, each with a lit upper edge."""
        step = 0
        y = top
        while y < bottom:
            depth = min(5, bottom - y)
            inner = x_inner + (2 if step % 2 else 0)
            lo, hi = (min(x_outer, inner), max(x_outer, inner))
            for yy in range(y, y + depth):
                for xx in range(lo, hi + 1):
                    put(px, xx, yy, INK_MID)
            for xx in range(lo, hi + 1):
                put(px, xx, y, INK_SOFT)
            for xx in range(lo, hi + 1, 3):
                put(px, xx, y + depth - 1, INK)
            y += depth
            step += 1

    cliff(max(fall_x - 12, 0), fall_x - 4, 6, 28)
    cliff(min(fall_x + 12, w - 1), fall_x + 4, 9, 28)

    # the water: a sheet that widens as it falls, brightest in the middle
    for y in range(6, 27):
        spread = 3 + (y - 6) // 8
        for x in range(fall_x - spread, fall_x + spread + 1):
            edge = abs(x - fall_x) >= spread
            put(px, x, y, WATER_PALE if edge else FOAM)
    # the lip, dark, where the river goes over
    for x in range(fall_x - 4, fall_x + 5):
        put(px, x, 5, WATER_DEEP)
        put(px, x, 6, WATER)

    # where it lands: a splash, then rings that widen and fade
    cluster(px, fall_x, 27, 4.0, FOAM, WATER_PALE)
    for i, r in enumerate((6, 9, 12)):
        for x in range(fall_x - r, fall_x + r + 1):
            if (x + i) % 2 == 0 and 0 <= x < w:
                put(px, x, 28 + i, WATER_PALE if i else FOAM)

    # a pine on the clifftop, which is what gives the drop its scale
    lesser_pine(px, max(fall_x - 10, 2), 6, 5, INK_MID, ((-2, 2, 3.0, 0.2), (2, -1, 2.5, 0.4)))
    if width_blocks >= 4:
        far_hill(px, w - 12, 26, 12, 7)
        lesser_pine(px, w - 9, 26, 9, INK_SOFT, ((-2, 3, 3.0, 0.2), (2, -1, 2.5, 0.4)))
        lesser_pine(px, w - 20, 27, 7, INK_SOFT, ((-2, 2, 2.5, 0.2),))
    seal_mark(px, w - 6, 26)
    return px


# ------------------------------------------------------------------ mountain
def mountain(width_blocks):
    """Ridges one behind another, each fainter, with snow left as paper on the nearest."""
    px = canvas(width_blocks)
    w = 16 * width_blocks
    peak_x = 13 if width_blocks < 4 else 22

    # the near mountain, drawn as two slopes meeting
    stroke(px, [(peak_x - 12, 29), (peak_x - 4, 16), (peak_x, 10)], 1.2, 0.8, INK)
    stroke(px, [(peak_x, 10), (peak_x + 6, 18), (peak_x + 13, 29)], 1.2, 0.8, INK)
    # Solid, with a fold or two picked out. A diagonal pattern across the face read as hatching
    # rather than as stone.
    for y in range(11, 29):
        span = int((y - 10) * 0.95)
        for x in range(peak_x - span, peak_x + span):
            if px[y][x] == NONE:
                put(px, x, y, INK_MID)
    for k in range(4, 18, 4):
        stroke(px, [(peak_x - k // 2, 12 + k), (peak_x - k, 20 + k)], 0.6, 0.4, INK)
    # snow: the top left unpainted, with a broken line under it
    for y in range(10, 15):
        for x in range(peak_x - (y - 9), peak_x + (y - 9)):
            put(px, x, y, SNOW if (x * 3 + y) % 5 else INK_WASH)

    far_hill(px, peak_x - 16, 29, 12, 7)
    if width_blocks >= 4:
        far_hill(px, w - 12, 29, 14, 9)
        far_hill(px, w - 26, 30, 10, 5)
        moon(px, w - 8, 7, 4)
    seal_mark(px, w - 6, 26)
    return px


# ------------------------------------------------------------------ moon
def moon_night(width_blocks):
    """A harvest moon over reeds and water.

    The pine's moon is unpainted paper, which is how it is done in ink -- but a picture whose
    whole subject is the moon cannot be made of nothing. This one is painted: warm yellow with a
    halo, over reeds and water in night blue.
    """
    px = canvas(width_blocks)
    w = 16 * width_blocks
    cx = 20 if width_blocks < 4 else 40

    for dy in range(-8, 9):
        for dx in range(-8, 9):
            d = math.hypot(dx, dy)
            if d <= 5.4:
                put(px, cx + dx, 11 + dy, MOONLIGHT)
            elif d <= 7.4 and (dx * 3 + dy * 5) % 3:
                put(px, cx + dx, 11 + dy, MOON_HALO)

    # cloud bands across it, which is what makes it a moon rather than a lamp
    for band_y, x0, x1 in ((9, cx - 11, cx + 6), (14, cx - 6, cx + 11)):
        for x in range(x0, x1):
            if (x * 3) % 5 > 1:
                put(px, x, band_y, NIGHT)

    # water below, in flat night-blue lines
    for i, y in enumerate(range(26, 31)):
        for x in range(1, w - 1):
            if (x + i * 3) % 4 > 0:
                put(px, x, y, NIGHT_DEEP if i % 2 else NIGHT)

    reeds = [(4, 30, 14), (7, 30, 19), (11, 30, 12), (15, 30, 17)]
    if width_blocks >= 4:
        reeds += [(22, 30, 15), (27, 30, 21), (31, 30, 13),
                  (w - 14, 30, 18), (w - 9, 30, 12), (w - 5, 30, 20)]
    for x, base_y, top in reeds:
        lean = 2 if x % 2 else -2
        stroke(px, [(x, base_y), (x + lean // 2, (base_y + top) // 2), (x + lean, top)],
               0.7, 0.35, NIGHT_DEEP)
        for k in range(2):
            leaf(px, x + lean, top + k * 3, x + lean * 3, top + 2 + k * 4, NIGHT, 0.4)

    for bx, by in ((cx - 14, 5), (cx - 10, 3)):
        put(px, bx, by, NIGHT_DEEP)
        put(px, bx - 1, by - 1, NIGHT_DEEP)
        put(px, bx + 1, by - 1, NIGHT_DEEP)
    seal_mark(px, w - 6, 26)
    return px


# ------------------------------------------------------------------ koi
def koi(width_blocks):
    """Carp in water, seen from above.

    This panel was a crane twice, standing and then flying, and neither read as a bird at
    sixteen pixels to a block: too much of what makes a crane a crane is in its proportions, and
    proportions are the first thing to go at this size. A carp is a teardrop and a tail. It is
    also the older subject of the two on a screen at the water's edge.
    """
    px = canvas(width_blocks)
    w = 16 * width_blocks

    # the water first: flat bands, so the fish have something to be under
    for i, y in enumerate(range(2, 31, 4)):
        for x in range(1, w - 1):
            if (x + i * 3) % 5 > 1:
                put(px, x, y, WATER_PALE)

    def carp(cx, cy, length, tilt, body, marks):
        """One fish: a body that swells and tapers, a forked tail, two fins and an eye."""
        dx, dy = math.cos(tilt), -math.sin(tilt)
        for i in range(length):
            t = i / (length - 1.0)
            # widest a third of the way down, which is where a fish is widest
            radius = 0.7 + 2.1 * math.sin(min(t * 1.35, 1.0) * math.pi) ** 0.8
            blot(px, round(cx + dx * (i - length / 2)), round(cy + dy * (i - length / 2)),
                 radius, body)
        tail_x = cx + dx * (length / 2 + 1)
        tail_y = cy + dy * (length / 2 + 1)
        for spread in (-1.0, 1.0):
            stroke(px, [(tail_x, tail_y),
                        (tail_x + dx * 3 - dy * 3 * spread, tail_y + dy * 3 + dx * 3 * spread)],
                   1.2, 0.5, body)
        # fins, one each side, halfway along
        for spread in (-1.0, 1.0):
            fin_x = cx - dy * 2 * spread
            fin_y = cy + dx * 2 * spread
            stroke(px, [(cx, cy), (fin_x - dx, fin_y - dy)], 0.9, 0.4, body)
        # the markings that make it a koi rather than a fish
        for k, place in enumerate((-0.15, 0.15, 0.4)):
            put(px, round(cx + dx * length * place), round(cy + dy * length * place), marks)
        head_x = round(cx - dx * (length / 2 - 1))
        head_y = round(cy - dy * (length / 2 - 1))
        put(px, head_x, head_y, INK)

    if width_blocks < 4:
        carp(13, 12, 13, -0.35, AUTUMN, FOAM)
        carp(18, 24, 10, 2.9, FOAM, AUTUMN)
    else:
        carp(14, 11, 14, -0.30, AUTUMN, FOAM)
        carp(24, 23, 12, 2.85, FOAM, AUTUMN)
        carp(42, 13, 13, 0.35, AUTUMN_DEEP, FOAM)
        carp(53, 24, 10, 3.05, WATER_PALE, WATER)

    # ripples, where a fish has just turned
    for cx, cy, r in (((22, 8, 5),) if width_blocks < 4 else ((32, 7, 5), (48, 27, 6))):
        for k in range(-r, r + 1):
            put(px, cx + k, cy - round(math.sqrt(max(r * r - k * k, 0)) * 0.4), WATER)
            put(px, cx + k, cy + round(math.sqrt(max(r * r - k * k, 0)) * 0.4), WATER)

    seal_mark(px, w - 6, 26)
    return px


def painting_item_texture(accent):
    """The painting rolled up: a sheet of paper with a stroke of its own motif on it.

    The accent is what tells nine scrolls apart in a row of inventory slots. Without it they
    were nine identical items and the player had to read every tooltip.
    """
    px = blank()
    paper = (232, 226, 208, 255)
    shade = (206, 198, 178, 255)
    for y in range(3, 14):
        for x in range(2, 14):
            px[y][x] = paper if x < 12 else shade
    for x in range(2, 14):
        px[3][x] = shade
        px[13][x] = shade
    for y, x0, x1 in ((6, 5, 10), (8, 6, 11), (10, 4, 9)):
        for x in range(x0, x1):
            px[y][x] = accent
    px[7][7] = INK
    px[9][8] = INK
    px[11][6] = INK
    return px


# Every painting: how to draw it, what crafts it, what it is called, and the colour its item
# carries. The ingredient is the motif -- it is what makes the recipe guessable without a wiki.
PAINTINGS = {
    "pine": (pine, "minecraft:spruce_sapling", "Pine", PINE_DARK),
    "bamboo": (bamboo, "minecraft:bamboo", "Bamboo", BAMBOO),
    "cherry": (cherry, "minecraft:cherry_sapling", "Cherry Blossom", BLOSSOM),
    "autumn": (autumn, "#minecraft:leaves", "Autumn Maple", AUTUMN),
    "wave": (wave, "minecraft:kelp", "Great Wave", WATER),
    "waterfall": (waterfall, "minecraft:prismarine_crystals", "Waterfall", WATER_PALE),
    "mountain": (mountain, "minecraft:flint", "Mountain", INK_MID),
    "moon": (moon_night, "minecraft:glowstone_dust", "Moon", MOONLIGHT),
    "koi": (koi, "minecraft:tropical_fish", "Koi", AUTUMN),
}

# The widths a sliding door comes in, and therefore the canvases each painting needs.
PAINTING_WIDTHS = (2, 4)


def write_paintings(lang):
    """Every painting, at every width a sliding door comes in."""
    written = 0
    for name, (draw, ingredient, label, accent) in PAINTINGS.items():
        for width in PAINTING_WIDTHS:
            write_png_sized(
                os.path.join(ASSETS, "textures", "block", "painting",
                             f"{name}_{width}.png"), draw(width))
            written += 1
        item = "fusuma_" + name
        write_png(os.path.join(ASSETS, "textures", "item", item + ".png"),
                  painting_item_texture(accent))
        write_json(os.path.join(ASSETS, "models", "item", item + ".json"),
                   {"parent": "item/generated", "textures": {"layer0": f"{MOD}:item/{item}"}})
        write_json(os.path.join(ASSETS, "items", item + ".json"),
                   {"model": {"type": "minecraft:model", "model": f"{MOD}:item/{item}"}})
        write_json(os.path.join(DATA, "recipe", item + ".json"), {
            "type": "minecraft:crafting_shapeless",
            "category": "misc",
            "ingredients": ["minecraft:paper", "minecraft:paper", ingredient],
            "result": {"id": f"{MOD}:{item}"},
        })
        lang[f"item.{MOD}.{item}"] = label + " Fusuma Painting"
        written += 4
    return written


def loot_table(block, width):
    """Only the anchor (lower half, column 0) drops anything: this prevents duplication when
    an explosion catches several columns. The same trick vanilla oak_door uses, widened to
    cover PART.

    A 1-wide door has no `part` property to name (D-38), and naming one it does not have makes
    the whole table fail to parse -- which means the door drops nothing at all. With one column
    there is nothing to disambiguate anyway: the lower half is the anchor."""
    anchor = {"half": "lower"} if width == 1 else {"half": "lower", "part": "0"}
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
                    "properties": anchor,
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
        for style in ("solid", "glazed"):
            for i in range(len(chain) - 1):
                oxidizables[f"{MOD}:{block_name(chain[i], width, style)}"] = {
                    "next_oxidation_stage": f"{MOD}:{block_name(chain[i + 1], width, style)}"
                }
            for bare, waxed in waxable_pairs():
                waxables[f"{MOD}:{block_name(bare, width, style)}"] = {
                    "waxed": f"{MOD}:{block_name(waxed, width, style)}"
                }

    write_json(os.path.join(root, "oxidizables.json"), {"values": oxidizables})
    write_json(os.path.join(root, "waxables.json"), {"values": waxables})
    return 2


# How many doors of the base width one batch makes.
#
# Four for the styles that run from width 1 to 4, where the ladder needs a batch of narrow doors
# to reach the wide ones. Two for the styles that exist only at 2 and 4: there, two of the base
# join into exactly one of the widest, so a batch is one wide door or two narrow ones. Making
# four would give two of the widest per batch, which is more door than a batch should buy.
BASE_YIELD = {(2, 4): 2}
DEFAULT_YIELD = 4


# How each set of widths is built up from the smallest one. The 4-wide recipe mirrors the
# mechanism: §2.3 defines it as two rigid leaves of half the width.
LADDER = {
    (1, 2, 3, 4): ((2, 1, ["DD"]), (3, 1, ["DDD"]), (4, 2, ["DD"])),
    (2, 4): ((4, 2, ["DD"]),),
}


def body_ingredient(material, craft, style):
    """What the door body is made of.

    Saloon doors take planks rather than whole logs: they are light, slatted things, and it
    keeps their recipe clear of the solid door's.
    """
    if style == "saloon":
        return f"minecraft:{material}_planks"
    # A shoji is a wooden frame around a panel. The glass one has no wood of its own, so its
    # frame takes any planks rather than picking one arbitrarily.
    if style in SLIDING:
        return "#minecraft:planks" if style == "sliding_glass" else f"minecraft:{material}_planks"
    return craft


# The materials mined with a pickaxe rather than an axe. Everything else here is wood, or
# close enough to it that vanilla treats it as wood.
METAL_IDS = {IRON_ID, GLASS_ID} | COPPER_IDS


def write_mineable_tags():
    """Puts every door in the tag for the tool that should break it.

    Without this a door belongs to no tool at all, and an axe gives no bonus on a wooden one --
    which does not change its hardness but does make it markedly slower to break than the vanilla
    door beside it. That was the symptom; the missing tag was the cause.

    These are written into minecraft's own namespace on purpose. A tag file there adds to the
    vanilla one rather than replacing it, which is how a datapack joins an existing set.
    """
    axe, pickaxe = [], []
    for style, (materials, widths) in STYLES.items():
        for material, label, texture, craft in materials:
            for width in widths:
                name = f"{MOD}:{block_name(material, width, style)}"
                (pickaxe if material in METAL_IDS else axe).append(name)

    for tool, values in (("axe", axe), ("pickaxe", pickaxe)):
        write_json(os.path.join(VANILLA_DATA, "tags", "block", "mineable", tool + ".json"),
                   {"values": sorted(values)})
    return 2


def write_recipes(material, craft, style, widths):
    n = 0

    def recipe(name, body):
        nonlocal n
        write_json(os.path.join(DATA, "recipe", name + ".json"), body)
        n += 1

    def named(width):
        return block_name(material, width, style)

    if style == "glazed":
        # Glazing upgrades the solid door of the same width, with as many glass blocks as the
        # door is wide. The door may sit anywhere in the row -- a shaped recipe has no "any
        # position", so it is one recipe per position, grouped in the book.
        panes = {
            1: [["G", "D"]],
            2: [["GG", "D "], ["GG", " D"]],
            3: [["GGG", "D  "], ["GGG", " D "], ["GGG", "  D"]],
            4: [[" G ", "GDG", " G "]],
        }
        for width in widths:
            name = named(width)
            for slot, pattern in enumerate(panes[width]):
                recipe(name if slot == 0 else f"{name}_{slot}", {
                    "type": "minecraft:crafting_shaped",
                    "category": "building",
                    "group": name,
                    "key": {"D": f"{MOD}:{block_name(material, width, 'solid')}",
                            "G": "minecraft:glass"},
                    "pattern": pattern,
                    "result": {"count": 1, "id": f"{MOD}:{name}"},
                })
        return n

    if style in SLIDING:
        # The recipe draws the door: the lintel above, the panel across the middle, the sill
        # below. No iron anywhere -- a shoji has neither hinge nor metal track, which is the one
        # place in this mod where the Iron Hinge is not the starting point.
        base = widths[0]
        glass = style == "sliding_glass"
        recipe(named(base), {
            "type": "minecraft:crafting_shaped",
            "category": "building",
            # The track along the head, the panel across the middle, the frame at the sill --
            # the recipe draws the door. Two columns rather than three: a sliding door is two
            # panels, and the grid may as well say so.
            "key": {"T": f"{MOD}:{TRACK}",
                    "P": "minecraft:glass" if glass else "minecraft:paper",
                    "F": "minecraft:iron_ingot" if glass
                         else body_ingredient(material, craft, style)},
            "pattern": ["TT", "PP", "FF"],
            "result": {"count": BASE_YIELD.get(tuple(widths), DEFAULT_YIELD),
                       "id": f"{MOD}:{named(base)}"},
        })
        for width, part, pattern in LADDER[tuple(widths)]:
            recipe(named(width), {
                "type": "minecraft:crafting_shaped",
                "category": "building",
                "key": {"D": f"{MOD}:{named(part)}"},
                "pattern": pattern,
                "result": {"count": 1, "id": f"{MOD}:{named(width)}"},
            })
        return n

    ingredient = body_ingredient(material, craft, style)
    base = widths[0]

    # The door body, four at a time. With the ladder this makes the widest door cost exactly
    # one batch. Oxidised and waxed copper have no body recipe -- they come from time or from
    # honeycomb -- but two narrow ones can still be joined into a wide one.
    #
    # A saloon door hangs on spring hinges, which is the one mechanism behind everything that
    # makes it different: it swings both ways, it shuts by itself, and redstone cannot hold it.
    # Two iron nuggets flank the hinge to pay for it, so the recipe says what the door does.
    if ingredient is not None:
        spring = style == "saloon"
        key = {"L": ingredient, "H": f"{MOD}:{HINGE}"}
        if spring:
            key["N"] = "minecraft:iron_nugget"
        recipe(named(base), {
            "type": "minecraft:crafting_shaped",
            "category": "building",
            "key": key,
            "pattern": ["LLN", "LLH", "LLN"] if spring else ["LL ", "LLH", "LL "],
            "result": {"count": BASE_YIELD.get(tuple(widths), DEFAULT_YIELD),
                       "id": f"{MOD}:{named(base)}"},
        })

    for width, part, pattern in LADDER[tuple(widths)]:
        recipe(named(width), {
            "type": "minecraft:crafting_shaped",
            "category": "building",
            "key": {"D": f"{MOD}:{named(part)}"},
            "pattern": pattern,
            "result": {"count": 1, "id": f"{MOD}:{named(width)}"},
        })
    return n


if __name__ == "__main__":
    main()
