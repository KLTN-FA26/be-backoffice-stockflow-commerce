package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The pieces the port's fidelity rests on: CPython's generator, the orientation codes, the per-type corners. */
class PortPrimitivesTest {

    @Test
    void drawsWhatCPythonDraws() {
        // Printed by CPython 3.9: r = random.Random(20260619); r.random() x3; shuffle(range(10)); choice; uniform.
        PyRandom r = new PyRandom(20260619);
        assertThat(r.random()).isEqualTo(0.5364235617225025);
        assertThat(r.random()).isEqualTo(0.546487252082444);
        assertThat(r.random()).isEqualTo(0.3394140731767046);
        List<Integer> l = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        r.shuffle(l);
        assertThat(l).containsExactly(8, 7, 4, 9, 2, 0, 5, 6, 3, 1);
        assertThat(r.choice(List.of(0.15, 0.3, 0.5))).isEqualTo(0.5);
        assertThat(r.uniform(0.02, 0.6)).isEqualTo(0.34886527721578303);
        assertThat(new PyRandom(0).random()).isEqualTo(0.8444218515250481);
        assertThat(new PyRandom((1L << 40) + 5).random()).isEqualTo(0.5043802970418443);
    }

    @Test
    void orientationIsTheFirstMatchingPermutation() {
        CargoPackage c = CargoPackage.of("c", 300, 100, 200, BigDecimal.ONE, true);   // (w, h, l) = (100, 200, 300)
        assertThat(Orientation.LWH.extents(c)).containsExactly(100, 200, 300);
        assertThat(Orientation.WLH.extents(c)).containsExactly(300, 200, 100);
        for (Orientation o : Orientation.values()) {
            int[] e = o.extents(c);
            assertThat(Orientation.of(c, e[0], e[1], e[2])).isEqualTo(o);
            assertThat(Orientation.ofCode(o.code())).isEqualTo(o);
        }
        CargoPackage cube = CargoPackage.of("q", 500, 500, 500, BigDecimal.ONE, false);
        assertThat(Orientation.of(cube, 500, 500, 500)).isEqualTo(Orientation.LWH);
        CargoPackage flat = CargoPackage.of("f", 400, 400, 100, BigDecimal.ONE, true);   // (w, h, l) = (400, 100, 400)
        assertThat(Orientation.of(flat, 400, 400, 100)).isEqualTo(Orientation.HWL);
    }

    @Test
    void cornerBlocksComeFromTheVehicleType() {
        VehicleSpec pickup = new VehicleSpec(LoadPlanAssertions.TRUCK, "PICKUP", VehicleKind.PICKUP, 1500, 1450, 500,
                new BigDecimal("900"), 1, 0);
        assertThat(Gussets.of(new Bin(pickup))).isEmpty();
        VehicleSpec truck = new VehicleSpec(LoadPlanAssertions.TRUCK, "TRUCK-8T", VehicleKind.TRUCK, 7600, 2350, 2400,
                new BigDecimal("8000"), 1, 120);
        // The original's layout, sized by the type: two cubes at the top of the front wall ...
        assertThat(Gussets.of(new Bin(truck))).extracting(g -> List.of(g.x, g.y, g.z, g.w))
                .containsExactly(List.of(0.0, 2280.0, 0.0, 120.0), List.of(2230.0, 2280.0, 0.0, 120.0));
        // ... and on a 45-foot box, four at the 40-foot corner-post lines, 12 032 mm apart and centred.
        VehicleSpec hc45 = new VehicleSpec(LoadPlanAssertions.TRUCK, "45HC", VehicleKind.CONTAINER, 13556, 2352, 2698,
                new BigDecimal("27700"), 1, 150);
        assertThat(Gussets.of(new Bin(hc45))).extracting(g -> List.of(g.x, g.z))
                .containsExactly(List.of(0.0, 762.0), List.of(0.0, 12644.0), List.of(2202.0, 762.0),
                        List.of(2202.0, 12644.0));
    }
}
