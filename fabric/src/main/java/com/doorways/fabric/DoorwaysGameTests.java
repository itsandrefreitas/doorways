package com.doorways.fabric;

import com.doorways.test.DoorwayScenarios;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Wires the shared scenarios into Fabric's GameTest.
 *
 * <p>Registration only: the bodies live in {@link DoorwayScenarios}, written in pure vanilla API
 * so that NeoForge could register the same ones through its bus event. It does not yet -- the
 * scenarios run on Fabric alone. They cover shared code, and every method here is one line on
 * purpose: what would be duplicated between loaders is the wiring, never the test.
 *
 * <p>The methods have to be public, non-static, and take only a {@code GameTestHelper}; that is
 * the annotation's contract. {@code maxTicks} goes well above the default because two scenarios
 * wait on a timer: the redstone one for a displaced door's 5-tick polling, and the saloon one
 * for the spring's 40-tick return.
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

    @GameTest(maxTicks = TICKS)
    public void saloonSwingsAwayFromThePlayer(GameTestHelper helper) {
        DoorwayScenarios.saloonSwingsAwayFromThePlayer(helper);
    }

    @GameTest(maxTicks = TICKS)
    public void saloonKeepsItsHingeBothWays(GameTestHelper helper) {
        DoorwayScenarios.saloonKeepsItsHingeBothWays(helper);
    }

    @GameTest(maxTicks = TICKS)
    public void saloonClosesItself(GameTestHelper helper) {
        DoorwayScenarios.saloonClosesItself(helper);
    }

    @GameTest(maxTicks = TICKS)
    public void saloonRefusesTheBlockedSide(GameTestHelper helper) {
        DoorwayScenarios.saloonRefusesTheBlockedSide(helper);
    }

    @GameTest(maxTicks = TICKS)
    public void saloonIgnoresRedstone(GameTestHelper helper) {
        DoorwayScenarios.saloonIgnoresRedstone(helper);
    }
}
