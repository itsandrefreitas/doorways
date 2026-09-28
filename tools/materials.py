"""The materials and styles a door can be made in.

`texture` is the vanilla texture the palette is sampled from -- the colours are not invented,
they are taken from the material itself (see palettes.py). `craft` is the ingredient for the
door body: a log for the woods, a stem for the Nether ones, a block for bamboo, an **ingot**
for the metals. `craft = None` means the door is not crafted from scratch: it is obtained only
through oxidation or waxing.

Copper enters as 8 materials -- 4 oxidation stages x waxed/unwaxed -- because in Minecraft each
state is a distinct block. The waxed ones share their counterpart's texture.

The style table mirrors DoorStyle.java. The two have to agree on which combinations exist and
on how names are built, or a door ends up pointing at a texture that was never written.
"""

COPPER_STATES = [
    ("copper", "Copper", "copper_block"),
    ("exposed_copper", "Exposed Copper", "exposed_copper"),
    ("weathered_copper", "Weathered Copper", "weathered_copper"),
    ("oxidized_copper", "Oxidized Copper", "oxidized_copper"),
]


def _copper():
    out = []
    for i, (name, label, texture) in enumerate(COPPER_STATES):
        # Only unoxidised copper is crafted from ingots; the other states come from time.
        out.append((name, label, texture, "minecraft:copper_ingot" if i == 0 else None))
    for name, label, texture in COPPER_STATES:
        out.append((f"waxed_{name}", f"Waxed {label}", texture, None))
    return out


# A wooden door is built from **planks**, in every style. It used to be whole logs, which made
# a door cost four times what it does now and put the two nether woods and bamboo in the odd
# position of naming a stem or a block instead of a plank. Planks are also what a door has been
# made of in vanilla since there were doors.
WOODS = [
    # id            name            vanilla texture      body ingredient
    ("oak",        "Oak",         "oak_planks",        "minecraft:oak_planks"),
    ("spruce",     "Spruce",      "spruce_planks",     "minecraft:spruce_planks"),
    ("birch",      "Birch",       "birch_planks",      "minecraft:birch_planks"),
    ("jungle",     "Jungle",      "jungle_planks",     "minecraft:jungle_planks"),
    ("acacia",     "Acacia",      "acacia_planks",     "minecraft:acacia_planks"),
    ("dark_oak",   "Dark Oak",    "dark_oak_planks",   "minecraft:dark_oak_planks"),
    ("mangrove",   "Mangrove",    "mangrove_planks",   "minecraft:mangrove_planks"),
    ("cherry",     "Cherry",      "cherry_planks",     "minecraft:cherry_planks"),
    ("pale_oak",   "Pale Oak",    "pale_oak_planks",   "minecraft:pale_oak_planks"),
    ("bamboo",     "Bamboo",      "bamboo_planks",     "minecraft:bamboo_planks"),
    ("crimson",    "Crimson",     "crimson_planks",    "minecraft:crimson_planks"),
    ("warped",     "Warped",      "warped_planks",     "minecraft:warped_planks"),
]

IRON = ("iron", "Iron", "iron_block", "minecraft:iron_ingot")
COPPER = _copper()

# Styles with no material to vary. The palette still comes from a vanilla texture: the glass
# frame borrows glass's own pale tone, the bookshelf its planks and book spines.
#
# The glass door is built from glass blocks rather than panes. Six panes are two blocks' worth,
# which would make it the cheapest door in the mod by a wide margin.
GLASS = ("glass", "Glass", "glass", "minecraft:glass")
BOOKSHELF = ("bookshelf", "Bookshelf", "bookshelf", "minecraft:bookshelf")

# Cut stone, in the two tones the game already has: ordinary cobblestone and the same thing
# made of deepslate, which is the darker one.
STONES = [
    ("cobblestone", "Cobblestone", "cobblestone", "minecraft:cobblestone"),
    ("cobbled_deepslate", "Cobbled Deepslate", "cobbled_deepslate", "minecraft:cobbled_deepslate"),
]

MATERIALS = WOODS + [IRON] + COPPER + [GLASS, BOOKSHELF] + STONES

# Which ids belong to a pickaxe rather than an axe. A bookshelf door is wood with books in it.
IRON_ID = IRON[0]
GLASS_ID = GLASS[0]
COPPER_IDS = {name for name, _, _, _ in COPPER}
STONE_IDS = {name for name, _, _, _ in STONES}

# style -> (materials, widths, heights). Mirrors DoorStyle.materialsFor, DoorStyle.allowsWidth
# and DoorStyle.heights.
#
# Height is an axis of the catalogue exactly as width is: each one is a separate block, not a
# property of a door. Nearly everything is two rows tall, which is what every door was before
# there were tall ones.
STYLES = {
    "solid":      (WOODS + [IRON] + COPPER, (1, 2, 3, 4), (2,)),
    "glazed":     (WOODS + [IRON] + COPPER, (1, 2, 3, 4), (2,)),
    "full_glass": ([GLASS], (1, 2, 3, 4), (2,)),
    # A saloon door is two swinging leaves, so it only exists at the even widths. No iron and
    # no copper: it is a wooden thing.
    "saloon":     (WOODS, (2, 4), (2,)),
    "bookshelf":  ([BOOKSHELF], (1, 2, 3, 4), (2,)),
    # A sliding leaf is always two panels, one hiding behind the other, so two columns make one
    # leaf and four make two. Nothing else divides evenly. No iron either: a fusuma runs in
    # wooden grooves and has neither hinge nor metal track.
    "fusuma":      (WOODS, (2, 4), (2,)),
    "sliding_glass": ([GLASS], (2, 4), (2,)),
    # The first style with a height of its own, and narrow on purpose: a leaf of cut stone four
    # columns wide, turning on a hinge, is not a door anybody would believe.
    "stone":      (STONES, (1, 2), (2, 3)),
}

STYLE_INFIX = {
    "solid": "",
    "glazed": "_glass",
    "full_glass": "",
    "saloon": "_saloon",
    "bookshelf": "",
    "fusuma": "_fusuma",
    "sliding_glass": "_sliding",
    # No infix: the material already says stone, and no solid door is made of cobblestone, so
    # cobblestone_doorway_1 collides with nothing.
    "stone": "",
}

STYLE_LABEL = {
    "solid": "",
    "glazed": " Glass",
    "full_glass": "",
    "saloon": " Saloon",
    "bookshelf": "",
    "fusuma": " Fusuma",
    "sliding_glass": " Sliding",
    "stone": "",
}

# The styles whose panels slide behind each other instead of turning. Mirrors DoorStyle.slides().
SLIDING = ("fusuma", "sliding_glass")

WIDTH_SUFFIX = {1: "", 2: " ×2", 3: " ×3", 4: " ×4"}

# The height every door was, and still is unless its style says otherwise.
DEFAULT_HEIGHT = 2

# What a door taller than usual is called. Mirrors nothing in the code -- it is only a label.
HEIGHT_PREFIX = {2: "", 3: "Tall ", 4: "Tall ", 5: "Tall "}


# The styles whose head is a round arch, at any height that has room for one. Mirrors
# DoorStyle.arched.
ARCHED = ("stone",)


def row_kinds(style, heights):
    """The kinds of row a style needs textures for.

    Three at most, however tall the door: the one on the ground, the one at the head, and any in
    between. A five-row gate has three middle rows and they are the same row three times, which
    is what keeps the number of files from growing with the height.

    An arched style needs a fourth, because the two heights would otherwise share the same top
    row -- and a round head belongs only to the taller one, which is the only one you can walk
    under.
    """
    kinds = ["bottom", "top"]
    if max(heights) > DEFAULT_HEIGHT:
        kinds.insert(1, "middle")
        if style in ARCHED:
            kinds.append("arch")
    return kinds


def roles(widths):
    """The column roles a style's widths actually call for.

    A door one column wide is a "single"; wider ones have an end at each side and, from three
    columns up, a smooth "mid" in between. Generating the roles a style can never reach would
    leave textures nothing points at, which the asset checker is right to refuse.
    """
    out = []
    if 1 in widths:
        out.append("single")
    if max(widths) >= 2:
        out += ["left", "right"]
    if max(widths) >= 3:
        out.append("mid")
    return out


def block_name(material, width, style, height=DEFAULT_HEIGHT):
    """State prefixes go in front, as in vanilla: waxed_exposed_copper_doorway_2.

    The height appears only when it is not the usual two, so every id written before there were
    tall doors still names the same block. `2x3` is width by height, in that order.
    """
    size = f"{width}" if height == DEFAULT_HEIGHT else f"{width}x{height}"
    return f"{material}{STYLE_INFIX[style]}_doorway_{size}"


def model_stem(material, style, half, role, swung=False):
    """The model and texture stem for one row of one column.

    `half` is a row kind -- "bottom", "middle" or "top" -- and not a half any more; the name is
    kept because it is the same slot in every stem the mod has ever written.

    Glazed doors are the exception: only the top row differs from a solid door, so the one below
    reuses the solid texture instead of duplicating it per material.

    Every door needs two models per stem, the way vanilla does: opening turns the leaf the other
    way about its hinge, which reverses the texture across it. The plain stem is the closed one
    and `_open` its swung counterpart -- vanilla's own door_bottom_left and
    door_bottom_left_open. A saloon door differs in the box as well as the UVs, since it hangs
    centred in its frame and lies flush once swung.

    The texture is always named by the plain stem: the box and the UVs move, the pixels do not.

    DoorStyle.modelStem mirrors this, and the two must agree or a blockstate ends up pointing at
    a model nobody wrote.
    """
    infix = "" if (style == "glazed" and half != "top") else STYLE_INFIX[style]
    stem = f"{material}{infix}_doorway_{half}_{role}"

    # A sliding door has no swung model. Its panels never turn, so there is no texture to
    # mirror; what its models say instead is which track a panel is on, and that is already in
    # the role -- "front", "back" or "stacked".
    if style in SLIDING:
        return stem
    return stem + "_open" if swung else stem


def display_name(label, width, style, height=DEFAULT_HEIGHT):
    return (f"{HEIGHT_PREFIX[height]}{label}{STYLE_LABEL[style]}"
            f" Doorway{WIDTH_SUFFIX[width]}")


def waxable_pairs():
    """(unwaxed, waxed) for each oxidation state."""
    return [(name, f"waxed_{name}") for name, _, _ in COPPER_STATES]


def oxidation_chain():
    """The four states in order, to link each one to the next."""
    return [name for name, _, _ in COPPER_STATES]
