package com.stockflow.fulfillment.internal.domain.loadplanning;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.assertSound;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc20;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.dc40;
import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.hc40;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Budget benchmark (LP2-10): 100, 300 and 500 mixed cartons with sets, at budgets of 3, 5, 8 and 15 s against the
 * 30 s reference; vehicles, volume fill, time to the best plan and total time. Off in CI:
 *
 * <pre>mvn -o test -Dtest=LoadPlannerBenchmarkTest -Dloadplanning.benchmark=true</pre>
 *
 * <p>Prints a Markdown table. The recommended default is the smallest budget whose vehicle count equals the 30 s
 * reference on every fixture, with volume fill within one percentage point of it.</p>
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "loadplanning.benchmark", matches = "true")
class LoadPlannerBenchmarkTest {

    private static final int[] SIZES = {100, 300, 500};
    private static final int[] BUDGETS = {3, 5, 8, 15, 30};

    @Test
    void budgets() {
        double rate = Double.parseDouble(System.getProperty("loadplanning.rate",
                Double.toString(LoadPlanner.TICKS_PER_SECOND)));
        LoadPlanner planner = new LoadPlanner(rate);
        StringBuilder table = new StringBuilder(String.format(Locale.ROOT,
                "%nwork rate %.0f/s%n| mode | cartons | budget s | vehicles | volume fill %% | unplaced | best at ms "
                        + "| total ms | exhausted |%n|---|---|---|---|---|---|---|---|---|%n", rate));
        List<String> verdicts = new ArrayList<>();
        for (SelectionMode mode : SelectionMode.values()) {
            for (int n : SIZES) {
                List<CargoPackage> cartons = LoadPlannerPropertyTest.randomCartons(new Random(1000 + n), n, true);
                double[] reference = null;
                List<double[]> rows = new ArrayList<>();
                for (int b = BUDGETS.length - 1; b >= 0; b--) {
                    LoadPlanRequest req = new LoadPlanRequest(cartons,
                            List.of(dc20(30, 150), dc40(15, 150), hc40(15, 150)), mode,
                            LoadPlannerPropertyTest.KITS, KitDistribution.AUTO, false, null, 0L,
                            Duration.ofSeconds(BUDGETS[b]));
                    LoadPlanResult r = planner.plan(req);
                    assertSound(req, r);
                    long volume = 0;
                    long capacity = 0;
                    for (PlannedVehicle v : r.vehicles()) {
                        volume += v.usedVolumeMm3();
                        for (VehicleSpec t : req.vehicleTypes()) {
                            if (t.typeId().equals(v.vehicleTypeId())) {
                                capacity += t.innerVolumeMm3();
                            }
                        }
                    }
                    double fill = capacity == 0 ? 0 : 100.0 * volume / capacity;
                    double[] row = {BUDGETS[b], r.vehicles().size(), fill, r.unplaced().size(),
                            r.meta().bestFoundMillis(), r.meta().elapsedMillis(), r.meta().budgetExhausted() ? 1 : 0};
                    if (reference == null) {
                        reference = row;
                    }
                    rows.add(0, row);
                }
                for (double[] row : rows) {
                    table.append(String.format(Locale.ROOT, "| %s | %d | %.0f | %.0f | %.2f | %.0f | %.0f | %.0f | %s |%n",
                            mode, n, row[0], row[1], row[2], row[3], row[4], row[5], row[6] > 0 ? "yes" : "no"));
                    boolean ok = row[1] == reference[1] && row[3] == reference[3] && Math.abs(row[2] - reference[2]) <= 1.0;
                    verdicts.add(mode + "/" + n + "/" + (int) row[0] + "=" + (ok ? "ok" : "worse"));
                }
            }
        }
        System.out.println(table);
        System.out.println(verdicts);
        assertThat(verdicts).isNotEmpty();
    }
}
