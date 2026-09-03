package com.doorways.block;

import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.DoorMode;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.WeatheringCopper.WeatherState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.jspecify.annotations.Nullable;

/**
 * One door: material × width × style.
 *
 * <p>Only the <b>definition</b> lives here. Registration itself does not fit in this module:
 * Fabric registers directly at start-up, NeoForge requires its own event. Each loader iterates
 * {@link #ALL} its own way.
 */
public record DoorVariant(Material material, int width, DoorMode mode, DoorStyle style) {

    /**
     * A door's material.
     *
     * <p>{@link BlockSetType} is the piece that matters: it brings each wood's vanilla opening,
     * closing and step sounds, plus {@code canOpenByHand}. An iron door sounds like iron and a
     * bamboo door sounds like bamboo with no extra code.
     */
    public record Material(String name, BlockSetType type, MapColor color,
                           float strength, boolean flammable, SoundType sound,
                           @Nullable WeatherState weathering) {}

    private static Material wood(String name, BlockSetType type, Block planks) {
        return new Material(name, type, planks.defaultMapColor(), 3.0F, true,
                type.soundType(), null);
    }

    /** Nether woods do not burn. */
    private static Material netherWood(String name, BlockSetType type, Block planks) {
        return new Material(name, type, planks.defaultMapColor(), 3.0F, false,
                type.soundType(), null);
    }

    private static Material metal(String name, BlockSetType type, Block block) {
        return new Material(name, type, block.defaultMapColor(), 5.0F, false,
                type.soundType(), null);
    }

    /**
     * One copper oxidation state.
     *
     * <p>A non-null {@code weathering} means the door ticks the clock. Waxed ones pass
     * {@code null}: they keep their stage's appearance but stop changing, which is what wax does.
     */
    private static Material copper(String name, Block sample, @Nullable WeatherState weathering) {
        return new Material(name, BlockSetType.COPPER, sample.defaultMapColor(), 5.0F, false,
                BlockSetType.COPPER.soundType(), weathering);
    }

    private static List<Material> copperStates() {
        var bare = Blocks.COPPER_BLOCK.weathering();
        var waxed = Blocks.COPPER_BLOCK.waxed();
        return List.of(
                copper("copper", bare.unaffected(), WeatherState.UNAFFECTED),
                copper("exposed_copper", bare.exposed(), WeatherState.EXPOSED),
                copper("weathered_copper", bare.weathered(), WeatherState.WEATHERED),
                copper("oxidized_copper", bare.oxidized(), WeatherState.OXIDIZED),
                copper("waxed_copper", waxed.unaffected(), null),
                copper("waxed_exposed_copper", waxed.exposed(), null),
                copper("waxed_weathered_copper", waxed.weathered(), null),
                copper("waxed_oxidized_copper", waxed.oxidized(), null));
    }

    /** The twelve woods, bamboo and the two Nether ones included. */
    public static final List<Material> WOODS = List.of(
            wood("oak", BlockSetType.OAK, Blocks.OAK_PLANKS),
            wood("spruce", BlockSetType.SPRUCE, Blocks.SPRUCE_PLANKS),
            wood("birch", BlockSetType.BIRCH, Blocks.BIRCH_PLANKS),
            wood("jungle", BlockSetType.JUNGLE, Blocks.JUNGLE_PLANKS),
            wood("acacia", BlockSetType.ACACIA, Blocks.ACACIA_PLANKS),
            wood("dark_oak", BlockSetType.DARK_OAK, Blocks.DARK_OAK_PLANKS),
            wood("mangrove", BlockSetType.MANGROVE, Blocks.MANGROVE_PLANKS),
            wood("cherry", BlockSetType.CHERRY, Blocks.CHERRY_PLANKS),
            wood("pale_oak", BlockSetType.PALE_OAK, Blocks.PALE_OAK_PLANKS),
            wood("bamboo", BlockSetType.BAMBOO, Blocks.BAMBOO_PLANKS),
            netherWood("crimson", BlockSetType.CRIMSON, Blocks.CRIMSON_PLANKS),
            netherWood("warped", BlockSetType.WARPED, Blocks.WARPED_PLANKS));

    private static final Material IRON = metal("iron", BlockSetType.IRON, Blocks.IRON_BLOCK);

    /**
     * Glass throughout, in an iron frame.
     *
     * <p>Strength follows the wooden doors rather than vanilla glass. A door at glass's 0.3
     * would break in a single punch, which makes it useless as a door -- and this is a door
     * before it is glass.
     */
    private static final Material GLASS = new Material("glass", BlockSetType.OAK,
            Blocks.GLASS.defaultMapColor(), 3.0F, false, SoundType.GLASS, null);

    private static final Material BOOKSHELF = new Material("bookshelf", BlockSetType.OAK,
            Blocks.BOOKSHELF.defaultMapColor(), 1.5F, true, SoundType.WOOD, null);

    /** Every material a door can be made of, in registration order. */
    public static final List<Material> MATERIALS = buildMaterials();

    private static List<Material> buildMaterials() {
        List<Material> all = new ArrayList<>(WOODS);
        all.add(IRON);
        all.addAll(copperStates());
        all.add(GLASS);
        all.add(BOOKSHELF);
        return List.copyOf(all);
    }

    /** The materials a given style is available in. */
    private static List<Material> materialsFor(DoorStyle style) {
        return switch (style) {
            case SOLID, GLAZED -> {
                List<Material> all = new ArrayList<>(WOODS);
                all.add(IRON);
                all.addAll(copperStates());
                yield List.copyOf(all);
            }
            // Neither iron nor copper: a saloon door is a wooden thing, and a fusuma runs in
            // wooden grooves, with no hinge and no metal track to make one out of.
            case SALOON, FUSUMA -> WOODS;
            case FULL_GLASS, SLIDING_GLASS -> List.of(GLASS);
            case BOOKSHELF -> List.of(BOOKSHELF);
        };
    }

    public static final List<DoorVariant> ALL = buildAll();

    private static List<DoorVariant> buildAll() {
        List<DoorVariant> all = new ArrayList<>();
        for (DoorStyle style : DoorStyle.values()) {
            for (Material material : materialsFor(style)) {
                for (int width = DoorLayout.MIN_WIDTH; width <= DoorLayout.MAX_WIDTH; width++) {
                    if (style.allowsWidth(width)) {
                        all.add(new DoorVariant(material, width, style.modeFor(width), style));
                    }
                }
            }
        }
        return List.copyOf(all);
    }

    /** Finds a variant by material name, width and style. */
    public static Optional<DoorVariant> find(String material, int width, DoorStyle style) {
        return ALL.stream()
                .filter(v -> v.material.name().equals(material)
                        && v.width == width && v.style == style)
                .findFirst();
    }

    public String name() {
        return style.name(material.name(), width);
    }

    public Identifier id(String modId) {
        return Identifier.fromNamespaceAndPath(modId, name());
    }

    public ResourceKey<Block> blockKey(String modId) {
        return ResourceKey.create(Registries.BLOCK, id(modId));
    }

    public ResourceKey<Item> itemKey(String modId) {
        return ResourceKey.create(Registries.ITEM, id(modId));
    }

    /**
     * Builds the block.
     *
     * <p>{@code setId} has to be called before constructing -- it replaces the
     * {@code valueLookupBuilder} removed in 26.2.
     */
    public WideDoorBlock createBlock(String modId) {
        BlockBehaviour.Properties base = BlockBehaviour.Properties.of()
                .mapColor(material.color())
                .instrument(NoteBlockInstrument.BASS)
                .strength(material.strength())
                .noOcclusion()
                .pushReaction(PushReaction.DESTROY)
                .sound(style == DoorStyle.GLAZED ? SoundType.GLASS : material.sound())
                .setId(blockKey(modId));

        BlockBehaviour.Properties properties = material.flammable() ? base.ignitedByLava() : base;
        // Every door is built through sized(): the state definition needs the width before the
        // constructor can hold one. This is the path registration takes; the codecs take the
        // same one.
        if (material.weathering() != null) {
            return WideDoorBlock.sized(width, mode, () -> new WeatheringWideDoorBlock(
                    width, mode, style, material.type(), material.weathering(),
                    properties.randomTicks()));
        }
        // A class of its own for one property, and another for one value. Carrying either on
        // every door -- to serve the 26 that slide and the 24 that swing both ways -- cost the
        // mod more blockstates than the whole of vanilla has.
        if (style.slides()) {
            return WideDoorBlock.sized(width, mode, () ->
                    new SlidingDoorBlock(width, mode, style, material.type(), properties));
        }
        if (style.springLoaded()) {
            return WideDoorBlock.sized(width, mode, () ->
                    new SpringDoorBlock(width, mode, style, material.type(), properties));
        }
        return WideDoorBlock.sized(width, mode, () ->
                new WideDoorBlock(width, mode, style, material.type(), properties));
    }
}
