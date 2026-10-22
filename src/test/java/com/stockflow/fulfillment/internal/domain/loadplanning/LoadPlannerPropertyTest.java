package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.assertSound;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc20;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc40;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.hc40;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.truck5t;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeded random inputs (jqwik is not a dependency, so each case is a {@code java.util.Random} seed): whatever the
 * cartons, fleet, mode and sets, the plan passes every check of {@link LoadPlanAssertions#assertSound} - piece check,
 * BR-10 re-validated from the output, payload, BR-12, whole sets per vehicle, orientations, load order - and the same
 * input and seed give the same plan. Run at the original's work rate (8 constructions a budget-second): the properties do
 * not depend on how long the search runs, and the cases stay quick and well inside their budget.
 */
class LoadPlannerPropertyTest {

    static final List<KitSpec> KITS = List.of(new KitSpec("K1", Map.of("K1-A", 1, "K1-B", 1)),
            new KitSpec("K2", Map.of("K2-A", 1, "K2-B", 2)));

    private static final int[][] SIZES = {{600, 400, 400}, {800, 600, 500}, {1200, 800, 1000}, {500, 500, 500},
        {1000, 600, 700}, {300, 300, 300}, {1500, 1000, 800}, {700, 500, 450}, {400, 300, 250}, {2400, 1100, 900}};

    /** Mixed cartons with real-looking, all-different weights; with kits, whole sets of K1 and K2 among them. */
    static List<CargoPackage> randomCartons(Random rnd, int n, boolean kits) {
        List<CargoPackage> out = new ArrayList<>();
        int i = 0;
        if (kits) {
            int k1 = 1 + n / 25;
            int k2 = 1 + n / 40;
            for (int s = 0; s < k1; s++) {
                out.add(carton(rnd, "k1a-" + s, new int[] {1100, 700, 900}, "K1-A"));
                out.add(carton(rnd, "k1b-" + s, new int[] {800, 500, 600}, "K1-B"));
            }
            for (int s = 0; s < k2; s++) {
                out.add(carton(rnd, "k2a-" + s, new int[] {1590, 1590, 90}, "K2-A"));
                out.add(carton(rnd, "k2b1-" + s, new int[] {730, 730, 800}, "K2-B"));
                out.add(carton(rnd, "k2b2-" + s, new int[] {730, 730, 800}, "K2-B"));
            }
            i = out.size();
        }
        for (; i < n; i++) {
            int[] s = SIZES[rnd.nextInt(SIZES.length)];
            out.add(carton(rnd, "c" + i, s, "SKU-" + s[0] + "x" + s[1]));
        }
        return out;
    }

    private static CargoPackage carton(Random rnd, String ref, int[] s, String part) {
        BigDecimal kg = BigDecimal.valueOf(2000 + rnd.nextInt(40000), 3);
        // Estimate-style refs on every third carton: several cartons of one line, each "<lineId>#<n>". Derived from
        // the ref, not drawn, so the cartons stay the ones the benchmark's Python reference ran on.
        String line = Math.floorMod(ref.hashCode(), 3) == 0 ? "line-" + part : null;
        return new CargoPackage(line == null ? ref : line + "#" + ref, line, s[0], s[1], s[2], kg,
                rnd.nextInt(5) > 0 || part.startsWith("K"), part, part.startsWith("K") ? "kit-" + ref : null,
                "SO-" + rnd.nextInt(4), false);
    }

    static Stream<Arguments> cases() {
        return IntStream.range(0, 24).mapToObj(seed -> {
            Random rnd = new Random(seed);
            int n = 20 + rnd.nextInt(180);
            boolean kits = seed % 2 == 0;
            int gusset = rnd.nextBoolean() ? 150 : rnd.nextInt(3) * 100;
            List<VehicleSpec> fleet = switch (seed % 4) {
                case 0 -> List.of(dc20(1 + rnd.nextInt(4), gusset));
                case 1 -> List.of(dc20(3, gusset), dc40(2, gusset));
                case 2 -> List.of(truck5t(2 + rnd.nextInt(6), gusset), dc20(1, gusset));
                default -> List.of(hc40(1, gusset), dc40(1, gusset), dc20(2, gusset), truck5t(3, gusset));
            };
            SelectionMode mode = seed % 3 == 0 ? SelectionMode.SINGLE_TYPE : SelectionMode.FEWEST_VEHICLES;
            KitDistribution dist = KitDistribution.values()[seed % 3];
            return Arguments.of(seed, new LoadPlanRequest(randomCartons(rnd, n, kits), fleet, mode,
                    kits ? KITS : List.of(), dist, seed % 5 == 0, null, seed, Duration.ofSeconds(20)));
        });
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("cases")
    void everyPlanIsSoundAndReproducible(int seed, LoadPlanRequest request) {
        LoadPlanResult first = new LoadPlanner(8.0).plan(request);
        assertThat(request.seed()).as("case %d carries its seed", seed).isEqualTo(seed);
        assertSound(request, first);
        assertThat(first.meta().budgetExhausted()).as("finished within its budget, so it must be reproducible")
                .isFalse();

        LoadPlanResult second = new LoadPlanner(8.0).plan(request);
        assertThat(second.vehicles()).isEqualTo(first.vehicles());
        assertThat(second.unplaced()).isEqualTo(first.unplaced());
        assertThat(second.meta().workTicks()).isEqualTo(first.meta().workTicks());
    }
}
