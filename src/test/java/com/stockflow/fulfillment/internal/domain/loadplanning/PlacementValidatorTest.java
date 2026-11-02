package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.carton;
import static org.assertj.core.api.Assertions.assertThat;

/** Each hard rule of BR-10 on its own, as a manual move would break it (BR-11). */
class PlacementValidatorTest {

    /** 2000 long, 1000 wide, 1000 high, 100 kg, 100 mm corner clearance at the front wall. */
    private static final VehicleSpec BOX = new VehicleSpec(LoadPlanAssertions.TRUCK, "T", VehicleKind.TRUCK,
            2000, 1000, 1000,
            new BigDecimal("100"), 1, 100);

    private static PlacementValidator.Item at(CargoPackage c, int x, int y, int z) {
        return PlacementValidator.Item.of(c, x, y, z, Orientation.LWH);
    }

    private static List<PlacementViolation> check(PlacementValidator.Item... items) {
        return PlacementValidator.validate(BOX, List.of(items));
    }

    private final CargoPackage a = carton("a", 500, 500, 500, "10", true, null);
    private final CargoPackage b = carton("b", 500, 500, 500, "10", true, null);

    @Test
    void cleanPlacementsPass() {
        assertThat(check(at(a, 0, 0, 500), at(b, 0, 500, 500))).isEmpty();
    }

    @Test
    void outOfBounds() {
        assertThat(check(at(a, 600, 0, 0))).containsExactly(new PlacementViolation("a", ViolationType.OUT_OF_BOUNDS));
        assertThat(check(at(a, 0, 0, -1))).containsExactly(new PlacementViolation("a", ViolationType.OUT_OF_BOUNDS));
    }

    @Test
    void overlapIsReportedForBoth() {
        assertThat(check(at(a, 0, 0, 0), at(b, 499, 0, 0))).containsExactly(
                new PlacementViolation("a", ViolationType.OVERLAP), new PlacementViolation("b", ViolationType.OVERLAP));
        assertThat(check(at(a, 0, 0, 0), at(b, 500, 0, 0))).isEmpty();
    }

    @Test
    void ninetySevenPercentIsUnsupportedNinetyEightIsNot() {
        CargoPackage floor = carton("floor", 1000, 800, 100, "1", false, null);
        CargoPackage top = carton("top", 100, 100, 100, "1", false, null);
        // top's base is 100 x 100 = 10 000 mm2; 3 mm past the floor carton's edge leaves 9 700 (97 %), 2 mm 9 800.
        assertThat(check(at(floor, 0, 0, 0), at(top, 703, 100, 0)))
                .containsExactly(new PlacementViolation("top", ViolationType.UNSUPPORTED));
        assertThat(check(at(floor, 0, 0, 0), at(top, 702, 100, 0))).isEmpty();
        assertThat(check(at(top, 0, 100, 0))).containsExactly(new PlacementViolation("top", ViolationType.UNSUPPORTED));
    }

    @Test
    void overweightIsTheVehicles() {
        CargoPackage heavy = carton("h", 500, 500, 500, "95", true, null);
        assertThat(check(at(heavy, 0, 0, 0), at(a, 500, 0, 0)))
                .containsExactly(new PlacementViolation(null, ViolationType.OVERWEIGHT));
    }

    @Test
    void overweightIsLeftOutOnlyWhenThePayloadIsIgnored() {
        CargoPackage heavy = carton("h", 500, 500, 500, "95", true, null);
        List<PlacementValidator.Item> load = List.of(at(heavy, 0, 0, 0), at(a, 500, 0, 0));
        assertThat(PlacementValidator.validate(BOX, load, false))
                .containsExactly(new PlacementViolation(null, ViolationType.OVERWEIGHT));
        assertThat(PlacementValidator.validate(BOX, load, true)).isEmpty();
        // The two-argument form is the real one.
        assertThat(PlacementValidator.validate(BOX, load)).containsExactly(
                new PlacementViolation(null, ViolationType.OVERWEIGHT));
        // Ignoring the payload leaves every other rule: an overlap is still reported.
        assertThat(PlacementValidator.validate(BOX, List.of(at(a, 0, 0, 0), at(b, 499, 0, 0)), true))
                .contains(new PlacementViolation("a", ViolationType.OVERLAP));
    }

    @Test
    void rotationNotAllowed() {
        CargoPackage fixed = carton("f", 600, 400, 300, "1", false, null);
        assertThat(PlacementValidator.validate(BOX, List.of(PlacementValidator.Item.of(fixed, 0, 0, 0,
                Orientation.LHW))))
                .containsExactly(new PlacementViolation("f", ViolationType.ROTATION_NOT_ALLOWED));
        assertThat(PlacementValidator.validate(BOX, List.of(new PlacementValidator.Item(fixed, 0, 0, 0, 400, 300,
                601)))).contains(new PlacementViolation("f", ViolationType.ROTATION_NOT_ALLOWED));
        CargoPackage free = carton("g", 600, 400, 300, "1", true, null);
        assertThat(PlacementValidator.validate(BOX, List.of(PlacementValidator.Item.of(free, 0, 0, 0,
                Orientation.LHW)))).isEmpty();
    }

    @Test
    void gussetCornersAreReserved() {
        CargoPackage tall = carton("t", 500, 500, 950, "1", true, null);
        assertThat(check(at(tall, 0, 0, 0))).containsExactly(new PlacementViolation("t", ViolationType.GUSSET));
        assertThat(check(at(tall, 0, 0, 100))).isEmpty();
        assertThat(check(at(tall, 100, 0, 0))).isEmpty();
    }

    @Test
    void aKitGroupSpreadOverVehiclesIsAKitSplit() {
        CargoPackage top = new CargoPackage("top", null, 500, 500, 500, BigDecimal.ONE, true, "T", "set-1", null, false);
        CargoPackage base = new CargoPackage("base", null, 500, 500, 500, BigDecimal.ONE, true, "B", "set-1", null,
                false);
        CargoPackage other = carton("x", 500, 500, 500, "1", true, null);
        assertThat(PlacementValidator.validateKitGroups(List.of(List.of(at(top, 0, 0, 0), at(base, 500, 0, 0)),
                List.of(at(other, 0, 0, 0))))).isEmpty();
        assertThat(PlacementValidator.validateKitGroups(List.of(List.of(at(top, 0, 0, 0), at(other, 500, 0, 0)),
                List.of(at(base, 0, 0, 0))))).containsExactly(new PlacementViolation("top", ViolationType.KIT_SPLIT),
                new PlacementViolation("base", ViolationType.KIT_SPLIT));
    }

    @Test
    void aFaceLargerThanTheDoorIsADoorViolation() {
        VehicleSpec doored = new VehicleSpec(LoadPlanAssertions.TRUCK, "D", VehicleKind.TRUCK, 2000, 1000, 1000,
                new BigDecimal("100"), 1, 0, 800, 600);
        CargoPackage c = carton("c", 500, 500, 700, "1", true, null);    // (w, h, l) = (500, 700, 500)
        assertThat(PlacementValidator.validate(doored, List.of(PlacementValidator.Item.of(c, 0, 0, 0,
                Orientation.LWH)))).containsExactly(new PlacementViolation("c", ViolationType.DOOR));
        assertThat(PlacementValidator.validate(doored, List.of(PlacementValidator.Item.of(c, 0, 0, 0,
                Orientation.HLW)))).isEmpty();
    }
}
