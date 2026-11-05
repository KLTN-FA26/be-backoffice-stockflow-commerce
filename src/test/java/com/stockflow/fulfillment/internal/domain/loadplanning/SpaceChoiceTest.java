package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SCRUM-496: the planner's space choice - deepest, then lowest, then leftmost - whatever the draw. */
class SpaceChoiceTest {

    private static final Bin CUBE = new Bin(new VehicleSpec(new UUID(0, 7), "CUBE", VehicleKind.TRUCK, 1000, 1000,
            1000, new BigDecimal("1000000"), 1, 0));

    private static List<List<Double>> fill(SpaceChoice choice, long seed) {
        List<Box> boxes = List.of(
                new ItemType(0, "A", 1.0, false, 500, 500, 500).box(1),
                new ItemType(1, "B", 1.0, false, 400, 400, 400).box(1),
                new ItemType(2, "C", 1.0, false, 300, 300, 300).box(1));
        List<double[]> spaces = new ArrayList<>();
        spaces.add(new double[] {0, 0, 0, CUBE.w, CUBE.h, CUBE.d});
        List<BoxPlacement> placed = new ArrayList<>();
        BlockFiller.recreate(CUBE, boxes, new PyRandom(seed), 0.0, 300, spaces, new SupportGrid(), placed,
                new ArrayList<>(), new int[] {1, 1, 1}, KitSolver.SUPPORT_MIN, CUBE.payloadKg, new double[] {0.0},
                WorkClock.starting(WorkClock.Unit.EVALUATION, 1e6, 60_000_000_000L), choice);
        List<List<Double>> out = new ArrayList<>();
        for (BoxPlacement p : placed) {
            out.add(List.of(p.x, p.y, p.z));
        }
        return out;
    }

    @Test
    void eachCartonGoesToTheDeepestThenLowestCorner() {
        for (long seed = 0; seed < 20; seed++) {
            // A in the corner; B beside it on the floor against the front wall (as deep as on top of A, but lower);
            // C on B, against the front wall (as deep as on top of A, lower), not further back on the floor.
            assertThat(fill(SpaceChoice.IMPROVED, seed)).as("seed %d", seed).containsExactly(
                    List.of(0.0, 0.0, 0.0), List.of(500.0, 0.0, 0.0), List.of(500.0, 400.0, 0.0));
        }
    }

    @Test
    void parityModeStillDrawsAmongTheSpaces() {
        // The original's draw puts B in any of the three spaces A leaves: over the seeds it lands in more than one.
        java.util.Set<List<Double>> whereB = new java.util.HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            whereB.add(fill(SpaceChoice.PYTHON_PARITY, seed).get(1));
        }
        assertThat(whereB).hasSizeGreaterThan(1);
    }

    @Test
    void ordersByDepthThenHeightThenWidth() {
        assertThat(SpaceChoice.before(new double[] {900, 900, 0}, new double[] {0, 0, 1})).isTrue();
        assertThat(SpaceChoice.before(new double[] {900, 0, 0}, new double[] {0, 1, 0})).isTrue();
        assertThat(SpaceChoice.before(new double[] {0, 0, 0}, new double[] {1, 0, 0})).isTrue();
        assertThat(SpaceChoice.before(new double[] {0, 0, 0}, new double[] {0, 0, 0})).isFalse();
    }
}
