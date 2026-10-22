package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.carton;
import static org.assertj.core.api.Assertions.assertThat;

/** Each hard rule of BR-10 on its own, as a manual move would break it (BR-11). */
class PlacementValidatorTest {

    /** 2000 long, 1000 wide, 1000 high, 100 kg, 100 mm corner clearance at the front wall. */
    private static final VehicleSpec BOX = new VehicleSpec(LoadPlanAssertions.TRUCK, "T", 2000, 1000, 1000,
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
}
