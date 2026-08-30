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
import net.minecraft.world.level.block.WeatheringCopper;
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
public record DoorVariant(Material material, int width, DoorMode mode, boolean glazed) {

    /**
     * A door's material.
     *
     * <p>{@link BlockSetType} is the piece that matters: it brings each wood's vanilla opening,
     * closing and step sounds, plus {@code canOpenByHand}. An iron door sounds like iron and a
     * bamboo door sounds like bamboo with no extra code.
     */
    public record Material(String name, BlockSetType type, MapColor color,
                           float strength, boolean flammable,
                           @Nullable WeatherState weathering) {}

    private static Material wood(String name, BlockSetType type, Block planks) {
        return new Material(name, type, planks.defaultMapColor(), 3.0F, true, null);
    }

    private static Material metal(String name, BlockSetType type, Block block) {
        return new Material(name, type, block.defaultMapColor(), 5.0F, false, null);
    }

    /**
     * One copper oxidation state.
     *
     * <p>A non-null {@code weathering} means the door ticks the clock. Waxed ones pass
     * {@code null}: they keep their stage's appearance but stop changing, which is what wax does.
     */
    private static Material copper(String name, Block sample,
                                   @Nullable WeatherState weathering) {
        return new Material(name, BlockSetType.COPPER, sample.defaultMapColor(), 5.0F, false,
                weathering);
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

    private static final List<Material> WOODS = List.of(
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
            // Nether woods do not burn.
            new Material("crimson", BlockSetType.CRIMSON,
                    Blocks.CRIMSON_PLANKS.defaultMapColor(), 3.0F, false, null),
            new Material("warped", BlockSetType.WARPED,
                    Blocks.WARPED_PLANKS.defaultMapColor(), 3.0F, false, null),
            metal("iron", BlockSetType.IRON, Blocks.IRON_BLOCK));

    /** Woods and iron, plus the eight copper states. */
    public static final List<Material> MATERIALS = concat(WOODS, copperStates());

    private static List<Material> concat(List<Material> a, List<Material> b) {
        List<Material> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    public static final List<DoorVariant> ALL = buildAll();

    private static List<DoorVariant> buildAll() {
        List<DoorVariant> all = new ArrayList<>();
        for (Material material : MATERIALS) {
            for (int width = DoorLayout.MIN_WIDTH; width <= DoorLayout.MAX_WIDTH; width++) {
                for (boolean glazed : new boolean[] {false, true}) {
                    all.add(new DoorVariant(material, width, DoorMode.defaultFor(width), glazed));
                }
            }
        }
        return List.copyOf(all);
    }

    /** Finds a variant by material name. */
    public static Optional<DoorVariant> find(String material, int width, boolean glazed) {
        return ALL.stream()
                .filter(v -> v.material.name().equals(material)
                        && v.width == width && v.glazed == glazed)
                .findFirst();
    }

    public String name() {
        return material.name() + (glazed ? "_glass_doorway_" : "_doorway_") + width;
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
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                .mapColor(material.color())
                .instrument(NoteBlockInstrument.BASS)
                .strength(material.strength())
                .noOcclusion()
                .pushReaction(PushReaction.DESTROY)
                .setId(blockKey(modId));

        if (material.flammable()) {
            properties = properties.ignitedByLava();
        }
        if (glazed) {
            properties = properties.sound(SoundType.GLASS);
        }
        if (material.weathering() != null) {
            return new WeatheringWideDoorBlock(width, mode, material.type(),
                    material.weathering(), properties.randomTicks());
        }
        return new WideDoorBlock(width, mode, material.type(), properties);
    }
}
