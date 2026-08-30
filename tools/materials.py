"""The v0.1 materials.

`texture` is the vanilla texture the palette is sampled from -- the colours are not invented,
they are taken from the material itself (see palettes.py). `craft` is the ingredient for the
door body: a log for the woods, a stem for the Nether ones, a block for bamboo, an **ingot**
for the metals. `craft = None` means the door is not crafted from scratch: it is obtained only
through oxidation or waxing.

Copper enters as 8 materials -- 4 oxidation stages x waxed/unwaxed -- because in Minecraft each
state is a distinct block. The waxed ones share their counterpart's texture.
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


MATERIALS = [
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
    ("iron",       "Iron",        "iron_block",        "minecraft:iron_ingot"),
] + _copper()

WIDTH_SUFFIX = {1: "", 2: " ×2", 3: " ×3", 4: " ×4"}


def block_name(material, width, glass):
    """State prefixes go in front, as in vanilla: waxed_exposed_copper_doorway_2."""
    return f"{material}_{'glass_' if glass else ''}doorway_{width}"


def display_name(label, width, glass):
    return f"{label}{' Glass' if glass else ''} Doorway{WIDTH_SUFFIX[width]}"


def waxable_pairs():
    """(unwaxed, waxed) for each oxidation state."""
    return [(name, f"waxed_{name}") for name, _, _ in COPPER_STATES]


def oxidation_chain():
    """The four states in order, to link each one to the next."""
    return [name for name, _, _ in COPPER_STATES]
