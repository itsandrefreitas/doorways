# -*- coding: utf-8 -*-
"""Draws the crafting recipes as animated GIFs for the store pages.

Run it with the client jar within reach:

    python tools/gen_recipes.py .

The output goes to `Screenshots/`, which is not committed: these are store images, published
on Modrinth and CurseForge rather than here.

Everything is drawn rather than captured. A GIF is the point: twelve wooden recipes collapse
into one image with the hinge standing still and the wood cycling under it, which is the fact
about this mod that a static picture cannot say.

The ingredient sprites and the font are read out of the client jar, the way `palettes.py`
already reads textures. That is the same call as a screenshot: it puts Mojang's pixels in an
image, and every recipe picture on every mod page does it.
"""
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from materials import BOOKSHELF, COPPER, GLASS, IRON, STONES, WOODS
from palettes import read_png
from targets import client_jar

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MOD_ITEMS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "doorways",
                         "textures", "item")
OUT = os.path.join(ROOT, "Screenshots")
CLIENT_JAR = client_jar(ROOT)

SCALE = 3
SLOT = 18           # a 16-pixel sprite with a pixel of border each side
GAP = 7
CAPTION = 186       # the caption column, wrapped to fit rather than counted by hand

INK = (232, 232, 232, 255)
DIM = (150, 150, 156, 255)
BACK = (34, 34, 38, 255)
SLOT_FILL = (58, 58, 64, 255)
SLOT_EDGE = (22, 22, 26, 255)
SLOT_LIT = (86, 86, 94, 255)
TITLE = (255, 214, 122, 255)


# ---------------------------------------------------------------- GIF


def lzw_encode(indices, min_code_size):
    clear = 1 << min_code_size
    end = clear + 1
    out = bytearray()
    bitbuf = bits = 0
    code_size = min_code_size + 1
    table = {}
    next_code = end + 1

    def emit(code):
        nonlocal bitbuf, bits
        bitbuf |= code << bits
        bits += code_size
        while bits >= 8:
            out.append(bitbuf & 0xFF)
            bitbuf >>= 8
            bits -= 8

    emit(clear)
    it = iter(indices)
    try:
        prefix = next(it)
    except StopIteration:
        emit(end)
        if bits:
            out.append(bitbuf & 0xFF)
        return bytes(out)

    for idx in it:
        key = (prefix, idx)
        if key in table:
            prefix = table[key]
            continue
        emit(prefix)
        if next_code < 4096:
            table[key] = next_code
            next_code += 1
            # The width grows when the next code to be assigned no longer fits. One off here
            # and every decoder in the world disagrees with us from that byte onwards.
            if next_code > (1 << code_size) and code_size < 12:
                code_size += 1
        else:
            emit(clear)
            table = {}
            code_size = min_code_size + 1
            next_code = end + 1
        prefix = idx
    emit(prefix)
    emit(end)
    if bits:
        out.append(bitbuf & 0xFF)
    return bytes(out)


def sub_blocks(data):
    out = bytearray()
    for i in range(0, len(data), 255):
        chunk = data[i:i + 255]
        out.append(len(chunk))
        out += chunk
    out.append(0)
    return bytes(out)


def colour_table(palette, bits):
    table = bytearray()
    for i in range(1 << bits):
        r, g, b = palette[i][:3] if i < len(palette) else (0, 0, 0)
        table += bytes((r, g, b))
    return bytes(table)


def write_gif(path, width, height, frames, delay_cs):
    """`frames` are canvases of RGBA tuples; each gets its own colour table.

    Per frame, and not one table for the lot, because a frame is one wood: twelve woods of
    sprites together run past 256 colours and would have to be quantised, which on pixel art
    looks like exactly what it is. Split by frame, each fits with room to spare.
    """
    out = bytearray(b"GIF89a")
    out += struct.pack("<HHBBB", width, height, 0x70, 0, 0)
    out += b"\x21\xFF\x0BNETSCAPE2.0\x03\x01\x00\x00\x00"
    for canvas in frames:
        flat = [px for row in canvas for px in row]
        palette = sorted({px[:3] for px in flat})
        if len(palette) > 256:
            raise ValueError("frame needs %d colours, GIF allows 256" % len(palette))
        index = {c: i for i, c in enumerate(palette)}
        bits = max(1, (len(palette) - 1).bit_length())
        out += b"\x21\xF9\x04\x04" + struct.pack("<H", delay_cs) + b"\x00\x00"
        out += b"\x2C" + struct.pack("<HHHHB", 0, 0, width, height, 0x80 | (bits - 1))
        out += colour_table(palette, bits)
        out.append(max(2, bits))
        out += sub_blocks(lzw_encode([index[px[:3]] for px in flat], max(2, bits)))
    out += b"\x3B"
    with open(path, "wb") as f:
        f.write(bytes(out))
    return len(out)


# ---------------------------------------------------------------- pixels


def canvas(width, height, fill=BACK):
    return [[fill] * width for _ in range(height)]


def blit(dst, src, x0, y0):
    """Draws a sprite, letting whatever is behind it show through where it is transparent."""
    for y, row in enumerate(src):
        for x, px in enumerate(row):
            if px[3] > 128 and 0 <= y0 + y < len(dst) and 0 <= x0 + x < len(dst[0]):
                dst[y0 + y][x0 + x] = px


def rect(dst, x0, y0, w, h, colour):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            if 0 <= y < len(dst) and 0 <= x < len(dst[0]):
                dst[y][x] = colour


def scaled(px, factor):
    return [[p for p in row for _ in range(factor)] for row in px for _ in range(factor)]


# ---------------------------------------------------------------- the font


def load_font(jar):
    """The vanilla ascii sheet: sixteen by sixteen cells of eight by eight.

    The advance is the drawn width plus one, which is what makes the text look like the game's
    rather than like a typewriter's.
    """
    w, h, flat = read_png(jar.read("assets/minecraft/textures/font/ascii.png"))
    cw, ch = w // 16, h // 16
    glyphs = {}
    for code in range(32, 127):
        cx, cy = (code % 16) * cw, (code // 16) * ch
        rows = [[flat[(cy + y) * w + cx + x][3] > 0 for x in range(cw)] for y in range(ch)]
        used = [x for x in range(cw) for y in range(ch) if rows[y][x]]
        advance = (max(used) + 2) if used else 4
        glyphs[chr(code)] = (rows, advance)
    return glyphs


def text_width(font, s):
    return sum(font[c][1] for c in s if c in font)


def wrap(font, s, width):
    """Breaks a caption to fit a column, measured in the font it will be drawn in.

    Counted by hand instead, the lines were wrong: two of them ran out under the crafting grid,
    which is the sort of thing that is obvious in the picture and invisible in the source.
    """
    lines, line = [], ""
    for word in s.split():
        candidate = word if not line else line + " " + word
        if text_width(font, candidate) <= width:
            line = candidate
        else:
            if line:
                lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def draw_text(dst, font, x, y, s, colour):
    for c in s:
        if c not in font:
            continue
        rows, advance = font[c]
        for dy, row in enumerate(rows):
            for dx, on in enumerate(row):
                if on and 0 <= y + dy < len(dst) and 0 <= x + dx < len(dst[0]):
                    dst[y + dy][x + dx] = colour
        x += advance
    return x


# ---------------------------------------------------------------- sprites


class Sprites:
    """Every 16x16 sprite the recipes need, ours and the game's, loaded once."""

    def __init__(self, jar):
        self.jar = jar
        self.cache = {}

    def _read(self, data):
        w, h, flat = read_png(data)
        return [flat[y * w:y * w + 16] for y in range(16)]

    def get(self, name):
        if name in self.cache:
            return self.cache[name]
        if name.startswith("doorways:"):
            path = os.path.join(MOD_ITEMS, name.split(":", 1)[1] + ".png")
            with open(path, "rb") as f:
                sprite = self._read(f.read())
        else:
            plain = name.split(":", 1)[-1]
            for folder in ("item", "block"):
                try:
                    sprite = self._read(
                        self.jar.read("assets/minecraft/textures/%s/%s.png" % (folder, plain)))
                    break
                except KeyError:
                    continue
            else:
                raise KeyError("no texture for " + name)
        self.cache[name] = sprite
        return sprite


# ---------------------------------------------------------------- the grid


def draw_slot(dst, x, y):
    rect(dst, x, y, SLOT, SLOT, SLOT_EDGE)
    rect(dst, x + 1, y + 1, SLOT - 2, SLOT - 2, SLOT_FILL)
    rect(dst, x + 1, y + 1, SLOT - 2, 1, SLOT_LIT)


def draw_arrow(dst, x, y, colour):
    rect(dst, x, y + 6, 9, 3, colour)
    for i in range(5):
        rect(dst, x + 8 + i, y + 3 + i, 1, 9 - 2 * i, colour)


def recipe_width():
    return 3 * SLOT + GAP + 14 + GAP + SLOT


def draw_recipe(dst, sprites, x, y, pattern, key, result):
    """A 3x3 grid, an arrow and the result. `pattern` rows are keys into `key`, space for none."""
    for r in range(3):
        for c in range(3):
            sx, sy = x + c * SLOT, y + r * SLOT
            draw_slot(dst, sx, sy)
            row = pattern[r] if r < len(pattern) else ""
            ch = row[c] if c < len(row) else " "
            if ch != " ":
                blit(dst, sprites.get(key[ch]), sx + 1, sy + 1)
    mid = y + (3 * SLOT - SLOT) // 2
    draw_arrow(dst, x + 3 * SLOT + GAP, mid, DIM)
    rx = x + 3 * SLOT + GAP + 14 + GAP
    draw_slot(dst, rx, mid)
    blit(dst, sprites.get(result), rx + 1, mid + 1)


# ---------------------------------------------------------------- the sheets
#
# One file is one timeline: everything on a canvas advances together, so a row may only share a
# file with rows that have something to change on the same beat. Every row here does -- the
# material, the width, the stone -- so they cycle as one and each frame is internally true.

ROW_GAP = 12

# The materials a door body is crafted from -- all sixteen. Glass and bookshelf are here
# because their doors use the same shape with their own ingredient, and saying so in a caption
# was not the same as showing it: an audit of all 333 recipes found them, the sliding glass door
# and the waxing missing from the pictures entirely.
#
# The other seven copper states are absent on purpose, and so is waxing. They come from time and
# from honeycomb rather than from a bench, and they are not what anyone opens a recipe sheet
# looking for: they happen to a door you already have.
MATERIALS = WOODS + [IRON, COPPER[0], GLASS, BOOKSHELF]


# Two singles make a double, three make a triple, two doubles make a quadruple.
LADDER = [(2, 1, ["DD "]), (3, 1, ["DDD"]), (4, 2, ["DD "])]

# Where the glass goes, by the width of the door under it.
PANES = {
    1: ["G  ", "D  "],
    2: ["GG ", "D  "],
    3: ["GGG", "D  "],
    4: [" G ", "GDG", " G "],
}


def material_of(frame):
    return MATERIALS[frame % len(MATERIALS)]


def any_planks(frame):
    """The planks for the recipes that take the tag: whichever wood this frame is showing.

    They were cycled on a stride of their own at first, on the reasoning that planks visibly
    unrelated to the door would prove the recipe accepts any of the twelve. It proved nothing
    and looked like a mistake: the heading said Oak, three rows showed oak, and the fourth
    showed jungle. A reader takes that for a bug, not for a demonstration. The twelve woods
    still all go past, and the caption says the rest.
    """
    mid = material_of(frame)[0]
    wood = any(mid == w[0] for w in WOODS)
    return (mid if wood else "oak") + "_planks"


def stone_of(frame):
    """Slowly, so the eye can follow it: the stone changes once a half-turn, not every frame."""
    return STONES[(frame // 7) % len(STONES)]


def base_rows(frame):
    """The left column: the four ways a door body is made."""
    mid, label, _, craft = material_of(frame)
    wood = any(mid == w[0] for w in WOODS)
    body = craft.split(":", 1)[1]
    planks = mid + "_planks"
    only_wood = "Wood only."
    # A door that is already glass all the way down, or a wall of books, has nothing to glaze.
    glazable = mid not in ("glass", "bookshelf")
    no_glazing = "Already glass all through." if mid == "glass" else "Not in bookshelf."

    return [
        ("Solid", "1x2  2x2  3x2  4x2",
         "Six of the material and a hinge, for four. Also makes the all-glass and bookshelf "
         "doors.",
         (["LL ", "LLH", "LL "], {"L": body, "H": "doorways:iron_hinge"},
          "doorways:%s_doorway_1" % mid)),

        ("Glazed", "1x2  2x2  3x2  4x2",
         "Glass in the upper half of a door you already have.",
         (PANES[1], {"G": "glass", "D": "doorways:%s_doorway_1" % mid},
          "doorways:%s_glass_doorway_1" % mid) if glazable else no_glazing),

        ("Saloon", "2x2  4x2",
         "Swings both ways and shuts itself. Two nuggets pay for the springs.",
         (["LLN", "LLH", "LLN"],
          {"L": planks, "N": "iron_nugget", "H": "doorways:iron_hinge"},
          "doorways:%s_saloon_doorway_2" % mid) if wood else only_wood),

        # One row, two doors: a fusuma in wood, the sliding glass door in glass. They are the
        # same mechanism with the paper swapped for a pane and the planks for ingots, and the
        # sliding glass door was in no picture at all before this.
        ("Fusuma and sliding glass" if wood or mid == "glass" else "Sliding", "2x2  4x2",
         "Slides instead of swinging. The papered one is the door you can paint.",
         (["TT ", "PP ", "FF "],
          {"T": "doorways:sliding_track",
           "P": "glass" if mid == "glass" else "paper",
           "F": "iron_ingot" if mid == "glass" else planks},
          "doorways:%s_%s_doorway_2" % (mid, "sliding" if mid == "glass" else "fusuma"))
         if wood or mid == "glass" else "Wood and glass only."),
    ]


def growing_rows(frame):
    """The right column: making them wider, and the stone doorway that is its own thing."""
    mid, label, _, _ = material_of(frame)
    width, part, pattern = LADDER[frame % len(LADDER)]
    pane = 2 + frame % 3
    stone_id, stone_label, _, stone_craft = stone_of(frame)
    stone = stone_craft.split(":", 1)[1]
    # Three steps on one row: joining two into a double, then raising each width by a course.
    # Drawn as two rows it took a quarter of the page for one idea; cycled, it costs nothing.
    step = frame % 3

    return [
        ("Wider", "2x2  3x2  4x2",
         "Two singles make a double, three a triple, two doubles a quadruple.",
         (pattern, {"D": "doorways:%s_doorway_%d" % (mid, part)},
          "doorways:%s_doorway_%d" % (mid, width))),

        ("Glazed wider", "2x2  3x2  4x2",
         "As many panes as the door is wide, and it may sit anywhere in the row.",
         (PANES[pane], {"G": "glass", "D": "doorways:%s_doorway_%d" % (mid, pane)},
          "doorways:%s_glass_doorway_%d" % (mid, pane))
         if mid not in ("glass", "bookshelf") else "Nothing to glaze."),

        # Named for its own stone, not for the wood in the heading: this half of the page is
        # on a different cycle and a reader should not have to work that out.
        ("%s doorway" % stone_label, "1x2  2x2",
         "A timber leaf in masonry that stays put. Planks of any wood.",
         (["LW ", "LWH", "LW "],
          {"L": stone, "W": any_planks(frame), "H": "doorways:iron_hinge"},
          "doorways:%s_doorway_1" % stone_id)),

        (("%s, wider" if step == 0 else "%s, three rows") % stone_label,
         ("2x2" if step == 0 else "1x3" if step == 1 else "2x3"),
         ("Two singles make a double, the same as everywhere else." if step == 0 else
          "One stone per column raises it a row, and gives it a round arch."),
         (["DD "], {"D": "doorways:%s_doorway_1" % stone_id},
          "doorways:%s_doorway_2" % stone_id) if step == 0 else
         (["DL " if step == 1 else "DLL"],
          {"D": "doorways:%s_doorway_%d" % (stone_id, step), "L": stone},
          "doorways:%s_doorway_%dx3" % (stone_id, step))),
    ]


def sheet(font, sprites, title, note, columns, frames_count, delay_cs, path,
          scale=SCALE, caption=CAPTION):
    """Draws one file: a title, then the columns side by side, one frame per turn of the cycle."""
    column_width = caption + GAP + recipe_width()
    rows = len(columns[0](0))
    width = len(columns) * column_width + (len(columns) + 1) * GAP
    height = 26 + rows * (3 * SLOT + ROW_GAP) - ROW_GAP + GAP
    frames = []
    for frame in range(frames_count):
        page = canvas(width, height)
        draw_text(page, font, GAP, 6, title, TITLE)
        # The name of what is cycling, and how far through. On one line: a second crosses the
        # rule under the title, and a viewer shown only the first frame still learns from the
        # counter that there are more.
        tag = note(frame)
        counter = " %d/%d" % (frame + 1, frames_count) if frames_count > 1 else ""
        x = width - GAP - text_width(font, tag + counter)
        if x < GAP + text_width(font, title) + GAP:
            raise ValueError("title and tag collide on frame %d of %s: shorten one"
                             % (frame, os.path.basename(path)))
        x = draw_text(page, font, x, 6, tag, TITLE)
        draw_text(page, font, x, 6, counter, DIM)
        rect(page, GAP, 18, width - 2 * GAP, 1, SLOT_EDGE)

        for col, build in enumerate(columns):
            left = GAP + col * (column_width + GAP)
            if col:
                rect(page, left - GAP // 2 - 1, 26, 1, height - 32, SLOT_EDGE)
            y = 26
            for name, sizes, text, recipe in build(frame):
                # The heading is wrapped to the column too, and everything below starts under
                # however many lines it took. Drawn on one line regardless, "Cobbled Deepslate
                # doorway" ran out over the crafting grid beside it.
                heading = wrap(font, name, caption - GAP)
                for i, line in enumerate(heading):
                    draw_text(page, font, left, y + i * 9, line, INK)
                below = y + len(heading) * 9 + 3
                # The sizes on a line of their own, in the heading colour. Folded into the
                # prose they were a clause the eye slides over; standing alone they are the
                # one thing a reader scans for. Width by height, which is how a doorway is
                # measured and how the mod's own ids are spelled.
                if sizes:
                    draw_text(page, font, left, below, sizes, TITLE)
                    below += 10
                lines = wrap(font, text, caption - GAP)
                for i, line in enumerate(lines):
                    draw_text(page, font, left, below + i * 9, line, DIM)
                # draw_text clips what falls off the canvas without a word, which is the worst
                # way for a caption to lose its last line: the picture still looks finished.
                bottom = below + len(lines) * 9
                if bottom > height:
                    raise ValueError(
                        "%s: the caption under %r runs %d pixels past the bottom"
                        % (os.path.basename(path), name, bottom - height))
                if isinstance(recipe, tuple):
                    draw_recipe(page, sprites, left + caption, y, *recipe)
                else:
                    # No such recipe in this material. Said in words where the grid would be,
                    # which is more use to a reader than an empty grid to interpret -- and
                    # wrapped to the grid's own width, or it runs into the next column.
                    for i, line in enumerate(wrap(font, recipe, recipe_width())):
                        draw_text(page, font, left + caption, y + 14 + i * 9, line, SLOT_LIT)
                y += 3 * SLOT + ROW_GAP
        frames.append(scaled(page, scale))
    size = write_gif(path, width * scale, height * scale, frames, delay_cs)
    print("%-22s %4d x %4d  %2d frames  %6.1f kB"
          % (os.path.basename(path), width * scale, height * scale, len(frames), size / 1024))


# A tag has no texture of its own, so one member stands for it in the picture. Azalea rather
# than oak: most leaf textures are grey and the game tints them by biome, so read straight out
# of the jar an oak leaf arrives white. Azalea carries its own green.
TAG_FACES = {"#minecraft:leaves": "azalea_leaves"}


def painting_rows(frame):
    """The components everything starts from, and the nine paintings."""
    from gen_assets import PAINTINGS
    names = sorted(PAINTINGS)
    name = names[frame % len(names)]
    _, ingredient, label, _ = PAINTINGS[name]
    face = TAG_FACES.get(ingredient, ingredient.split(":", 1)[-1])

    return [
        ("Iron Hinge", "",
         "Two ingots and two nuggets, laid across. Makes two, and every hinged door starts "
         "from one.",
         (["In ", "nI "], {"I": "iron_ingot", "n": "iron_nugget"}, "doorways:iron_hinge")),

        ("Sliding Track", "",
         "Two planks of any wood and a stick, for four. What a hinge is to the doors that "
         "swing.",
         (["LsL"], {"L": any_planks(frame), "s": "stick"}, "doorways:sliding_track")),

        ("%s" % label, "",
         "Two paper and the thing the picture is of, in any arrangement. Goes on a fusuma by "
         "hand, and comes off with a brush.",
         (["PPM"], {"P": "paper", "M": face}, "doorways:fusuma_%s" % name)),
    ]


def main():
    os.makedirs(OUT, exist_ok=True)
    jar = zipfile.ZipFile(CLIENT_JAR)
    font = load_font(jar)
    sprites = Sprites(jar)
    # Two columns at twice size rather than one at three times: a store page is about a
    # thousand pixels across, and anything wider is scaled down by the browser, which is the
    # one thing pixel art must never suffer.
    sheet(font, sprites, "Doorways -- how a door is made",
          lambda f: material_of(f)[1],
          [base_rows, growing_rows], len(MATERIALS), 100,
          os.path.join(OUT, "recipes-doors.gif"), scale=2, caption=150)

    from gen_assets import PAINTINGS
    # No tag on this one: the painting names itself in its own row heading, and repeating it
    # in the corner both duplicated it and ran into the title.
    sheet(font, sprites, "Doorways -- components and paintings",
          lambda f: "",
          [painting_rows], len(PAINTINGS), 110,
          os.path.join(OUT, "recipes-extras.gif"), scale=3, caption=186)


if __name__ == "__main__":
    main()
