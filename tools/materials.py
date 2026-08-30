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


WOODS = [
    # id            name            vanilla texture      body ingredient
    ("oak",        "Oak",         "oak_planks",        "minecraft:oak_log"),
    ("spruce",     "Spruce",      "spruce_planks",     "minecraft:spruce_log"),
    ("birch",      "Birch",       "birch_planks",      "minecraft:birch_log"),
    ("jungle",     "Jungle",      "jungle_planks",     "minecraft:jungle_log"),
    ("acacia",     "Acacia",      "acacia_planks",     "minecraft:acacia_log"),
    ("dark_oak",   "Dark Oak",    "dark_oak_planks",   "minecraft:dark_oak_log"),
    ("mangrove",   "Mangrove",    "mangrove_planks",   "minecraft:mangrove_log"),
    ("cherry",     "Cherry",      "cherry_planks",     "minecraft:cherry_log"),
    ("pale_oak",   "Pale Oak",    "pale_oak_planks",   "minecraft:pale_oak_log"),
    ("bamboo",     "Bamboo",      "bamboo_planks",     "minecraft:bamboo_block"),
    ("crimson",    "Crimson",     "crimson_planks",    "minecraft:crimson_stem"),
    ("warped",     "Warped",      "warped_planks",     "minecraft:warped_stem"),
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

MATERIALS = WOODS + [IRON] + COPPER + [GLASS, BOOKSHELF]

# style -> (materials, widths). Mirrors DoorStyle.materialsFor and DoorStyle.allowsWidth.
STYLES = {
    "solid":      (WOODS + [IRON] + COPPER, (1, 2, 3, 4)),
    "glazed":     (WOODS + [IRON] + COPPER, (1, 2, 3, 4)),
    "full_glass": ([GLASS], (1, 2, 3, 4)),
    # A saloon door is two swinging leaves, so it only exists at the even widths. No iron and
    # no copper: it is a wooden thing.
    "saloon":     (WOODS, (2, 4)),
    "bookshelf":  ([BOOKSHELF], (1, 2, 3, 4)),
}

STYLE_INFIX = {
    "solid": "",
    "glazed": "_glass",
    "full_glass": "",
    "saloon": "_saloon",
    "bookshelf": "",
}

STYLE_LABEL = {
    "solid": "",
    "glazed": " Glass",
    "full_glass": "",
    "saloon": " Saloon",
    "bookshelf": "",
}

WIDTH_SUFFIX = {1: "", 2: " ×2", 3: " ×3", 4: " ×4"}


def block_name(material, width, style):
    """State prefixes go in front, as in vanilla: waxed_exposed_copper_doorway_2."""
    return f"{material}{STYLE_INFIX[style]}_doorway_{width}"


def model_stem(material, style, half, role, swung=False):
    """The model and texture stem for one half of one column.

    Glazed doors are the exception: only the upper half differs from a solid door, so the lower
    half reuses the solid texture instead of duplicating it per material.

    Every door needs two models per stem, the way vanilla does: opening turns the leaf the other
    way about its hinge, which reverses the texture across it. The plain stem is the closed one
    and `_open` its swung counterpart -- vanilla's own door_bottom_left and
    door_bottom_left_open. A saloon door differs in the box as well as the UVs, since it hangs
    centred in its frame and lies flush once swung.

    The texture is always named by the plain stem: the box and the UVs move, the pixels do not.

    DoorStyle.modelStem mirrors this, and the two must agree or a blockstate ends up pointing at
    a model nobody wrote.
    """
    upper = half == "top"
    infix = "" if (style == "glazed" and not upper) else STYLE_INFIX[style]
    stem = f"{material}{infix}_doorway_{half}_{role}"
    return stem + "_open" if swung else stem


def display_name(label, width, style):
    return f"{label}{STYLE_LABEL[style]} Doorway{WIDTH_SUFFIX[width]}"


def waxable_pairs():
    """(unwaxed, waxed) for each oxidation state."""
    return [(name, f"waxed_{name}") for name, _, _ in COPPER_STATES]


def oxidation_chain():
    """The four states in order, to link each one to the next."""
    return [name for name, _, _ in COPPER_STATES]
