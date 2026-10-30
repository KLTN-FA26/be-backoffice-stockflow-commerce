package com.stockflow.fulfillment.internal.domain.loadplanning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanAssertions.assertSound;

/**
 * SCRUM-474 part 3: the planner on the datasets {@code tools/loadplanning/bench/gen_datasets.py} writes, to put next to
 * {@code run_python.py} (the lead's original) on the same cartons. Off in CI:
 *
 * <pre>mvn -o test -Dtest=LoadPlannerSolverBenchTest -Dloadplanning.solverbench=true
 *     [-Dloadplanning.datasets=mixed800,realistic800] [-Dloadplanning.budgets=5,30] [-Dloadplanning.seeds=0,1]
 *     [-Dloadplanning.config=parity|be] [-Dloadplanning.rate=3000000] [-Dloadplanning.datadir=DIR]
 *     [-Dloadplanning.space=IMPROVED|PYTHON_PARITY] [-Dloadplanning.wallcap=SECONDS]</pre>
 *
 * <p>Configs: {@code parity} is what the original models (a 40' high cube, 150 mm corner gussets, no payload or door
 * limit); {@code be} adds what the BE enforces on top (payload 26 500 kg with the cartons' measured weights, the
 * 2 340 x 2 585 mm door). {@code datadir} reads the datasets from a directory ({@code gen_datasets.py} writes them)
 * instead of the classpath, for the larger draws that are not committed.</p>
 *
 * <p>SCRUM-496: {@code space} picks the block filler's space choice ({@link SpaceChoice}; {@code PYTHON_PARITY} is the
 * original's, the planner's before SCRUM-496). {@code wallcap} replaces the request's budget as the real time cap only,
 * so a search runs to its end however busy the machine is and the plan depends on the work alone; each line also
 * reports the thread's CPU time, which a busy machine inflates less than the wall clock.</p>
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "loadplanning.solverbench", matches = "true")
class LoadPlannerSolverBenchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID HC = new UUID(0, 41);
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();

    @Test
    void run() throws Exception {
        String config = System.getProperty("loadplanning.config", "parity");
        String[] datasets = System.getProperty("loadplanning.datasets", "mixed800,realistic800").split(",");
        String[] budgets = System.getProperty("loadplanning.budgets", "5,30").split(",");
        String[] seeds = System.getProperty("loadplanning.seeds", "0").split(",");
        double rate = Double.parseDouble(System.getProperty("loadplanning.rate",
                Double.toString(LoadPlanner.TICKS_PER_SECOND)));
        SpaceChoice choice = SpaceChoice.valueOf(System.getProperty("loadplanning.space", "IMPROVED"));
        String cap = System.getProperty("loadplanning.wallcap");
        Duration wallCap = cap == null ? null : Duration.ofSeconds(Long.parseLong(cap));
        VehicleSpec hc = "be".equals(config)
                ? new VehicleSpec(HC, "40HC", VehicleKind.CONTAINER, 12032, 2352, 2698, new BigDecimal("26500"), 64,
                150, 2340, 2585)
                : new VehicleSpec(HC, "40HC", VehicleKind.CONTAINER, 12032, 2352, 2698, new BigDecimal("1000000000"),
                64, 150);
        for (String ds : datasets) {
            List<CargoPackage> cartons = load(ds);
            for (String b : budgets) {
              for (String seed : seeds) {
                LoadPlanRequest req = new LoadPlanRequest(cartons, List.of(hc), SelectionMode.FEWEST_VEHICLES,
                        List.of(), KitDistribution.AUTO, false, null, Long.parseLong(seed),
                        Duration.ofSeconds(Long.parseLong(b)));
                long cpu0 = CPU.getCurrentThreadCpuTime();
                LoadPlanResult r = new LoadPlanner(rate, choice, wallCap).plan(req);
                double cpu = (CPU.getCurrentThreadCpuTime() - cpu0) / 1e9;
                assertSound(req, r);
                List<String> fills = new ArrayList<>();
                double sum = 0;
                for (PlannedVehicle v : r.vehicles()) {
                    fills.add(v.volumeFillPct().toPlainString());
                    sum += v.volumeFillPct().doubleValue();
                }
                System.out.println(String.format(Locale.ROOT,
                        "BENCH {\"engine\":\"java\",\"space\":\"" + choice + "\",\"config\":\"%s\",\"dataset\":\"%s\",\"budget\":%s,\"seed\":%s,\"vehicles\":%d,"
                                + "\"fills\":%s,\"avg_fill\":%.2f,\"unplaced\":%d,\"seconds\":%.1f,\"cpu_seconds\":%.1f,\"exhausted\":%s,"
                                + "\"ticks\":%d}", config, ds, b, seed, r.vehicles().size(), fills,
                        r.vehicles().isEmpty() ? 0.0 : sum / r.vehicles().size(), r.unplaced().size(),
                        r.meta().elapsedMillis() / 1000.0, cpu, r.meta().budgetExhausted(), r.meta().workTicks()));
              }
            }
        }
    }

    private static List<CargoPackage> load(String name) throws Exception {
        String dir = System.getProperty("loadplanning.datadir");
        try (InputStream in = dir != null ? java.nio.file.Files.newInputStream(java.nio.file.Path.of(dir, name + ".json"))
                : LoadPlannerSolverBenchTest.class.getResourceAsStream("/loadplanning/bench/" + name + ".json")) {
            JsonNode root = JSON.readTree(in);
            List<CargoPackage> out = new ArrayList<>();
            for (JsonNode c : root.get("cartons")) {
                out.add(new CargoPackage(c.get("ref").asText(), null, c.get("l").asInt(), c.get("w").asInt(),
                        c.get("h").asInt(), BigDecimal.valueOf(c.get("kg").asDouble()), c.get("rot").asBoolean(),
                        c.get("part").asText(), null, null, false));
            }
            return out;
        }
    }
}
