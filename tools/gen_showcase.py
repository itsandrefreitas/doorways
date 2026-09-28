# -*- coding: utf-8 -*-
"""Draws every door in the mod as one wall, animated, for the store pages.

    python tools/gen_showcase.py .

Output goes to `Screenshots/`, which is not committed.

These are **elevations**, not renders: each door is assembled from the very block textures the
game draws it with, seen straight on. There is no perspective, no light and no open leaf,
because there is no game engine here to supply them. That is the honest division of labour with
a screenshot -- a screenshot shows a handful of doors open and lit in a world, and this shows
every one of them at once, which a screenshot never can.
"""
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from materials import BOOKSHELF, GLASS, STONES, WOODS, model_stem
from palettes import read_png
from gen_recipes import (BACK, CLIENT_JAR, DIM, GAP, INK, OUT, ROOT, SLOT_EDGE, TITLE,
                         canvas, draw_text, load_font, rect, scaled, text_width, write_gif)

TILE = 16
DOOR_GAP = 7          # daylight between two doors standing on the same shelf
ROW_GAP = 9
LABEL = 116           # the family name, down the left
SCALE = 3

ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "doorways")


def load_png(path):
    """A PNG as rows of RGBA, at whatever size it happens to be."""
    with open(path, "rb") as f:
        w, h, flat = read_png(f.read())
    return [flat[y * w:(y + 1) * w] for y in range(h)]


class Tiles:
    """The mod's own block textures, read once each."""

    def __init__(self):
        self.cache = {}

    def block(self, stem):
        if stem not in self.cache:
            self.cache[stem] = load_png(
                os.path.join(ASSETS, "textures", "block", stem + ".png"))
        return self.cache[stem]

    def painting(self, name, width):
        key = "painting/%s_%d" % (name, width)
        if key not in self.cache:
            self.cache[key] = load_png(
                os.path.join(ASSETS, "textures", "block", "painting",
                             "%s_%d.png" % (name, width)))
        return self.cache[key]


def row_kind(row, height, arched):
    if row == 0:
        return "bottom"
    if row < height - 1:
        return "middle"
    return "arch" if arched else "top"


def role_of(column, width, sliding):
    """A sliding door's panels are all the same; a swinging one has ends and a middle."""
    if sliding:
        return "panel"
    if width == 1:
        return "single"
    return "left" if column == 0 else ("right" if column == width - 1 else "mid")


def elevation(tiles, material, style, width, height, painting=None):
    """A door seen straight on, built from the tiles the game draws it with.

    Block rows count from the ground up and pixels count from the top down. That is the one
    place the two conventions meet, and the one place to get it wrong.
    """
    sliding = style in ("fusuma", "sliding_glass")
    arched = style == "stone" and height > 2
    out = [[BACK] * (width * TILE) for _ in range(height * TILE)]
    for row in range(height):
        kind = row_kind(row, height, arched)
        for column in range(width):
            tile = tiles.block(model_stem(material, style, kind,
                                          role_of(column, width, sliding)))
            top = (height - 1 - row) * TILE
            for y in range(TILE):
                for x in range(TILE):
                    px = tile[y][x]
                    if px[3] > 128:
                        out[top + y][column * TILE + x] = px
    if painting:
        # One picture across the whole door rather than one per panel, which is the thing about
        # these that a single-panel screenshot cannot show.
        art = tiles.painting(painting, width)
        for y in range(min(len(art), len(out))):
            for x in range(min(len(art[0]), len(out[0]))):
                if art[y][x][3] > 128:
                    out[y][x] = art[y][x]
    return out


# ---------------------------------------------------------------- the wall
#
# A row per family, and within a row every size it comes in, standing on the same floor. What
# cycles differs by row -- the woods for most, the two stones for one, the nine paintings for
# another -- and each row takes the frame number modulo its own list, so the wall is never
# half empty the way it would be if one material had to serve them all.

PAINTINGS = ("pine", "bamboo", "cherry", "autumn", "wave",
             "waterfall", "mountain", "moon", "koi")


def wall_rows(frame):
    wood = WOODS[frame % len(WOODS)]
    stone = STONES[frame % len(STONES)]
    art = PAINTINGS[frame % len(PAINTINGS)]
    return [
        ("Solid", wood[1], "solid", wood[0], [(1, 2), (2, 2), (3, 2), (4, 2)], None),
        ("Glazed", wood[1], "glazed", wood[0], [(1, 2), (2, 2), (3, 2), (4, 2)], None),
        ("Saloon", wood[1], "saloon", wood[0], [(2, 2), (4, 2)], None),
        ("Fusuma", wood[1], "fusuma", wood[0], [(2, 2), (4, 2)], None),
        ("Painted", art.title(), "fusuma", wood[0], [(2, 2), (4, 2)], art),
        ("Glass", "", "full_glass", GLASS[0], [(1, 2), (2, 2), (3, 2), (4, 2)], None),
        ("Sliding glass", "", "sliding_glass", GLASS[0], [(2, 2), (4, 2)], None),
        ("Bookshelf", "", "bookshelf", BOOKSHELF[0], [(1, 2), (2, 2), (3, 2), (4, 2)], None),
        ("Stone", stone[1], "stone", stone[0], [(1, 2), (2, 2), (1, 3), (2, 3)], None),
    ]


def main():
    os.makedirs(OUT, exist_ok=True)
    jar = zipfile.ZipFile(CLIENT_JAR)
    font = load_font(jar)
    tiles = Tiles()

    rows = wall_rows(0)
    shelf = [max(h for _, h in sizes) * TILE for _, _, _, _, sizes, _ in rows]
    span = max(sum(w * TILE for w, _ in sizes) + DOOR_GAP * (len(sizes) - 1)
               for _, _, _, _, sizes, _ in rows)
    width = LABEL + span + 2 * GAP
    height = 26 + sum(shelf) + ROW_GAP * len(rows) + GAP

    frames = []
    for frame in range(len(WOODS)):
        page = canvas(width, height)
        title = "Doorways -- every door in the mod"
        draw_text(page, font, GAP, 6, title, TITLE)
        counter = "%d/%d" % (frame + 1, len(WOODS))
        draw_text(page, font, width - GAP - text_width(font, counter), 6, counter, DIM)
        rect(page, GAP, 18, width - 2 * GAP, 1, SLOT_EDGE)

        y = 26
        for i, (name, note, style, material, sizes, painting) in enumerate(wall_rows(frame)):
            draw_text(page, font, GAP, y + shelf[i] - 20, name, INK)
            if note:
                draw_text(page, font, GAP, y + shelf[i] - 10, note, DIM)
            x = LABEL
            for w, h in sizes:
                door = elevation(tiles, material, style, w, h, painting)
                # Standing on the same floor, whatever their height: a three-row doorway is
                # taller than the rest and it should look it.
                top = y + shelf[i] - h * TILE
                for dy, line in enumerate(door):
                    for dx, px in enumerate(line):
                        page[top + dy][x + dx] = px
                x += w * TILE + DOOR_GAP
            y += shelf[i] + ROW_GAP
        frames.append(scaled(page, SCALE))

    size = write_gif(os.path.join(OUT, "doors.gif"), width * SCALE, height * SCALE, frames, 110)
    print("doors.gif  %d x %d  %d frames  %.1f kB"
          % (width * SCALE, height * SCALE, len(frames), size / 1024))


if __name__ == "__main__":
    main()
