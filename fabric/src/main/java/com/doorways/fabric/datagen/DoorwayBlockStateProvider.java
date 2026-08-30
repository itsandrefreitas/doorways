package com.doorways.fabric.datagen;

import com.doorways.Doorways;
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

    private static MultiVariantGenerator blockState(DoorVariant variant, WideDoorBlock door) {
        return MultiVariantGenerator.dispatch(door).with(
                PropertyDispatch.initial(
                                WideDoorBlock.FACING,
                                WideDoorBlock.HALF,
                                WideDoorBlock.HINGE,
                                WideDoorBlock.SWING,
                                WideDoorBlock.PART)
                        .generate((facing, half, hinge, swing, part) ->
                                variantFor(variant, door, facing, half, hinge, swing, part)));
    }

    private static MultiVariant variantFor(DoorVariant variant, WideDoorBlock door,
                                           Direction facing, DoubleBlockHalf half,
                                           DoorHingeSide hinge, DoorSwing swing, int part) {
        DoorLayout layout = DoorLayout.of(
                WideDoorGeometry.toCore(facing),
                door.width(),
                hinge == DoorHingeSide.LEFT ? Hinge.LEFT : Hinge.RIGHT);

        // PART goes up to 3 on every block (D-22), so narrow widths have states that never
        // exist in the world. Clamp it the way the block does, so the blockstate still covers
        // those states instead of blowing up during generation.
        int column = Math.min(part, door.width() - 1);

        MultiVariant model = BlockModelGenerators.plainVariant(modelId(variant, half, column, swing));
        Quadrant rotation = yRotation(WideDoorGeometry.leafDirection(
                layout, column, WideDoorGeometry.toCore(swing)));
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
    private static Identifier modelId(DoorVariant variant, DoubleBlockHalf half, int column,
                                      DoorSwing swing) {
        int width = variant.width();
        String role = width == 1 ? "single"
                : column == 0 ? "left"
                : column == width - 1 ? "right"
                : "mid";
        return Identifier.fromNamespaceAndPath(Doorways.MOD_ID, "block/"
                + variant.style().modelStem(variant.material().name(),
                        half == DoubleBlockHalf.UPPER, role, swing != DoorSwing.CLOSED));
    }
}
