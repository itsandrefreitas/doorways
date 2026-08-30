package com.doorways.fabric;

import com.doorways.test.DoorwayScenarios;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Wires the shared scenarios into Fabric's GameTest.
 *
 * <p>Registration only: the bodies live in {@link DoorwayScenarios}, in pure vanilla API, and
 * NeoForge registers the same ones through its bus event. Every method here is one line on
 * purpose -- what is duplicated between loaders is the wiring, never the test.
 *
 * <p>The methods have to be public, non-static, and take only a {@code GameTestHelper}; that is
 * the annotation's contract. {@code maxTicks} goes well above the default because the redstone
 * scenario waits for the displaced door's 5-tick polling.
 */
public class DoorwaysGameTests {

    private static final int TICKS = 200;

    @GameTest(maxTicks = TICKS)
    public void opensAndClosesWidthFour(GameTestHelper helper) {
        DoorwayScenarios.opensAndClosesWidthFour(helper);
    }

    @GameTest(maxTicks = TICKS)
    public void pillarInPathSurvives(GameTestHelper helper) {
        DoorwayScenarios.pillarInPathSurvives(helper);
    }

    @GameTest(maxTicks = TICKS)
    public void redstoneOpensAndCloses(GameTestHelper helper) {
        DoorwayScenarios.redstoneOpensAndCloses(helper);
    }

    @GameTest(maxTicks = TICKS)
    public void copperConversionSpreadsToWholeDoor(GameTestHelper helper) {
        DoorwayScenarios.copperConversionSpreadsToWholeDoor(helper);
    }

    @GameTest(maxTicks = TICKS)
    public void widthOneRespectsHinge(GameTestHelper helper) {
        DoorwayScenarios.widthOneRespectsHinge(helper);
    }
}
