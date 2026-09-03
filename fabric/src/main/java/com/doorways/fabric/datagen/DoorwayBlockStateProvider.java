package com.doorways.fabric.datagen;

import com.doorways.Doorways;
import com.doorways.block.DoorStyle;
import com.doorways.block.DoorSwing;
import com.doorways.block.DoorVariant;
import com.doorways.block.WideDoorBlock;
import com.doorways.block.WideDoorGeometry;
import com.doorways.core.geometry.DoorLayout;
import com.doorways.core.geometry.Hinge;
import com.mojang.math.Quadrant;
import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.renderer.block.dispatch.VariantMutator;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Generates the doors' 200 blockstates from the real geometry.
 *
 * <p>Blockstates decide which way a leaf faces, so they must derive from the same
 * {@link DoorLayout} and {@link WideDoorGeometry#leafDirection} the block uses in game. Any
 * second definition of that rule can drift, and a door that behaves one way while drawing
 * another fails silently: both halves stay internally consistent, so no test reports anything.
 *
 * <p>{@code POWERED} is left out of the dispatch deliberately (D-24): the keys omit it and each
 * variant serves both values, exactly as vanilla does. That leaves five properties, which is
 * exactly what {@code PropertyDispatch} supports -- and the reason {@code SWING} had to replace
 * the boolean {@code open} rather than sit alongside it. A sixth property could not be
 * generated at all.
 */
public class DoorwayBlockStateProvider extends FabricModelProvider {

    public DoorwayBlockStateProvider(FabricPackOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockModelGenerators generators) {
        for (DoorVariant variant : DoorVariant.ALL) {
            Block block = BuiltInRegistries.BLOCK.getValue(variant.blockKey(Doorways.MOD_ID));
            if (!(block instanceof WideDoorBlock door)) {
                throw new IllegalStateException("door not registered: " + variant.name());
            }
            generators.blockStateOutput.accept(blockState(variant, door));
            itemDefinition(generators, variant);
        }
    }

    /**
     * The item definition has to be explicit.
     *
     * <p>By default {@code ModelProvider} points the item at the block model with the same name,
     * following the vanilla convention. That model does not exist here: block models are per
     * <b>role</b> ({@code _bottom_left}, {@code _top_mid}, ...) and shared across widths, never
     * per variant. The item uses the flat sprite in {@code item/}.
     */
    private static void itemDefinition(BlockModelGenerators generators, DoorVariant variant) {
        Item item = BuiltInRegistries.ITEM.getValue(variant.itemKey(Doorways.MOD_ID));
        generators.itemModelOutput.accept(item, ItemModelUtils.plainModel(
                Identifier.fromNamespaceAndPath(Doorways.MOD_ID, "item/" + variant.name())));
    }

    /** The sprites and the models they feed still come from the texture generator. */
    @Override
    public void generateItemModels(ItemModelGenerators generators) {
    }

    /**
     * The dispatch, in the three shapes a door can have.
     *
     * <p>A door declares only the properties it reads (D-38), so the generator has to ask which
     * ones those are. A 1-wide door has no {@code part} -- one column needs no index -- and a
     * door that opens from the middle has no {@code hinge}, because each of its leaves turns
     * about its own outer end. The fourth combination cannot occur: a door that opens from the
     * middle is at least 2 wide.
     *
     * <p>This branching is the visible cost of the saving, and it is paid once, here.
     */
    private static MultiVariantGenerator blockState(DoorVariant variant, WideDoorBlock door) {
        IntegerProperty part = door.partProperty();
        EnumProperty<DoorHingeSide> hinge = door.hingeProperty();

        if (hinge == null) {
            return MultiVariantGenerator.dispatch(door).with(
                    PropertyDispatch.initial(
                                    WideDoorBlock.FACING,
                                    WideDoorBlock.HALF,
                                    door.swingProperty(),
                                    part)
                            .generate((facing, half, swing, column) -> variantFor(
                                    variant, door, facing, half,
                                    DoorHingeSide.LEFT, swing, column)));
        }
        if (part == null) {
            return MultiVariantGenerator.dispatch(door).with(
                    PropertyDispatch.initial(
                                    WideDoorBlock.FACING,
                                    WideDoorBlock.HALF,
                                    hinge,
                                    door.swingProperty())
                            .generate((facing, half, side, swing) ->
                                    variantFor(variant, door, facing, half, side, swing, 0)));
        }
        return MultiVariantGenerator.dispatch(door).with(
                PropertyDispatch.initial(
                                WideDoorBlock.FACING,
                                WideDoorBlock.HALF,
                                hinge,
                                door.swingProperty(),
                                part)
                        .generate((facing, half, side, swing, column) ->
                                variantFor(variant, door, facing, half, side, swing, column)));
    }

    private static MultiVariant variantFor(DoorVariant variant, WideDoorBlock door,
                                           Direction facing, DoubleBlockHalf half,
                                           DoorHingeSide hinge, DoorSwing swing, int part) {
        // Built from the block's own mode and the style's motion rather than from the defaults,
        // so that a sliding door is described here exactly as the game describes it.
        DoorLayout layout = new DoorLayout(
                WideDoorGeometry.toCore(facing),
                door.width(),
                door.mode(),
                hinge == DoorHingeSide.LEFT ? Hinge.LEFT : Hinge.RIGHT,
                variant.style().motion());

        MultiVariant model = BlockModelGenerators.plainVariant(
                modelId(variant, layout, half, part, swing));
        Quadrant rotation = yRotation(WideDoorGeometry.leafDirection(
                layout, part, WideDoorGeometry.toCore(swing)));
        return rotation == Quadrant.R0 ? model : model.with(VariantMutator.Y_ROT.withValue(rotation));
    }

    /**
     * The base model puts the leaf in the {@code x 0..3} slice, which corresponds to a door
     * facing <b>east</b> -- the same base orientation vanilla's door blockstates use.
     */
    private static Quadrant yRotation(Direction leaf) {
        return switch (leaf) {
            case EAST -> Quadrant.R0;
            case SOUTH -> Quadrant.R90;
            case WEST -> Quadrant.R180;
            case NORTH -> Quadrant.R270;
            default -> throw new IllegalArgumentException("leaf is not horizontal: " + leaf);
        };
    }

    /**
     * One column's model, shared across all widths.
     *
     * <p>Three rules. Only the end columns of the whole door carry a frame, and only on their
     * outer edges; the middle ones are smooth on both sides so leaves meet without a seam. The
     * glass is only in the <b>upper</b> half -- the lower one uses the plain model of the same
     * material, so no {@code *_glass_doorway_bottom_*} file exists. And every door has a second
     * model for the swung states, whose texture is mirrored across the leaf -- the same reason
     * vanilla ships {@code door_bottom_left_open} alongside {@code door_bottom_left}.
     */
    private static Identifier modelId(DoorVariant variant, DoorLayout layout,
                                      DoubleBlockHalf half, int column, DoorSwing swing) {
        DoorStyle style = variant.style();
        String material = variant.material().name();
        boolean upper = half == DoubleBlockHalf.UPPER;

        if (style.slides()) {
            return slidingModelId(style, material, layout, upper, column, swing);
        }

        int width = variant.width();
        String role = width == 1 ? "single"
                : column == 0 ? "left"
                : column == width - 1 ? "right"
                : "mid";
        return model(style.modelStem(material, upper, role, swing != DoorSwing.CLOSED));
    }

    /**
     * A sliding door's model, which says which track a panel is on rather than which way it
     * turned.
     *
     * <p>The four cases are the door standing still. Shut, the panel that will stay put is on
     * the near track and the one that will hide behind it is on the far one -- visibly offset,
     * which is what shows that the door slides. Open, the column the leaf parked in carries both
     * panels and every other column carries none.
     *
     * <p>Nothing here describes a panel in mid-travel, and nothing needs to. While one is
     * moving, {@code SlidingDoorBlock.SLIDING} makes it invisible and its renderer takes over; the moment
     * it stops, one of these four takes it back. That handover is what lets a sliding door be an
     * ordinary batched block for all but a third of a second at a time -- and, more visibly,
     * what keeps it drawn past 64 blocks, which is as far as a renderer reaches.
     */
    private static Identifier slidingModelId(DoorStyle style, String material, DoorLayout layout,
                                             boolean upper, int column, DoorSwing swing) {
        boolean parks = layout.parksHere(column);
        String track = swing == DoorSwing.CLOSED
                ? (parks ? "front" : "back")
                : (parks ? "stacked" : "hidden");
        return model(style.modelStem(material, upper, track));
    }

    private static Identifier model(String stem) {
        return Identifier.fromNamespaceAndPath(Doorways.MOD_ID, "block/" + stem);
    }
}
