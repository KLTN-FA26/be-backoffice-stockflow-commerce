package com.stockflow.fulfillment.internal.domain.loadplanning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The port against the original: each fixture was run through the lead's Python {@code solve_3d_kit} (an unmodified
 * copy, its clock patched to count constructions the way {@link WorkClock} does - {@code tools/loadplanning/parity.py})
 * and the result stored next to it. The Java solver must reproduce it exactly: the same vehicles, the same cartons at
 * the same corners in the same sizes, the same leftovers.
 *
 * <p>Exact, not "within a tolerance": both draw from the same Mersenne Twister ({@link PyRandom}) and run the same
 * arithmetic on whole millimetres, so any difference is a porting defect. The fixtures stay clear of the deliberate
 * departures (the kit-bypass fix, the docs' fleet ranking, BR-10's payload, the 1 mm tolerance), which have tests of
 * their own; the early stops are switched off here because the original has none, and the space choice is the
 * original's ({@link SpaceChoice#PYTHON_PARITY}; the planner's own, SCRUM-496, is a deliberate deviation).</p>
 */
class KitSolverParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode read(String name) throws IOException {
        try (InputStream in = KitSolverParityTest.class.getResourceAsStream("/loadplanning/parity/" + name)) {
            return JSON.readTree(in);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bulk_20dc_first", "bulk_40hc_gusset", "kit_bulk_even", "two_kits_auto",
            "non_rotatable_20dc", "many_lines_40hc", "kits_even_three_20dc", "bulk_large_40dc", "distinct_120_40hc"})
    void reproducesThePythonSolver(String name) throws IOException {
        JsonNode fx = read(name + ".fixture.json");
        JsonNode expected = read(name + ".expected.json");

        List<Bin> bins = new ArrayList<>();
        for (JsonNode c : fx.get("containers")) {
            bins.add(new Bin(new VehicleSpec(UUID.randomUUID(), c.get("code").asText(), VehicleKind.CONTAINER,
                    c.get("d").asInt(),
                    c.get("w").asInt(), c.get("h").asInt(), new BigDecimal("1000000000"), c.get("quantity").asInt(),
                    c.get("gusset").asInt())));
        }
        List<Box> boxes = new ArrayList<>();
        for (JsonNode b : fx.get("boxes")) {
            ItemType t = new ItemType(boxes.size(), b.get("part").asText(), b.get("weight").asDouble(),
                    b.get("rotatable").asBoolean(), b.get("w").asDouble(), b.get("h").asDouble(), b.get("d").asDouble());
            boxes.add(t.box(b.get("quantity").asInt()));
        }
        List<KitSpec> kits = new ArrayList<>();
        for (JsonNode k : fx.get("kits")) {
            Map<String, Integer> members = new LinkedHashMap<>();
            k.get("members").forEach(m -> members.put(m.get(0).asText(), m.get(1).asInt()));
            kits.add(new KitSpec(k.get("name").asText(), members));
        }
        KitDistribution distribution = KitDistribution.valueOf(fx.get("distribute").asText().toUpperCase());
        WorkClock clock = WorkClock.starting(WorkClock.Unit.CONSTRUCTION, fx.get("ticks_per_second").asDouble(),
                600_000_000_000L);

        KitSolver.Outcome out = new KitSolver(clock, 0L, false, SpaceChoice.PYTHON_PARITY).solve(bins, boxes, kits, null,
                fx.get("budget").asDouble(), LoadPlanner.ATTEMPTS, distribution, List.of(), false);

        List<List<List<Object>>> actual = new ArrayList<>();
        for (BinLoad l : out.placed()) {
            List<List<Object>> rows = new ArrayList<>();
            for (BoxPlacement p : l.placements()) {
                rows.add(List.of(p.x, p.y, p.z, p.w, p.h, p.d, p.partName()));
            }
            actual.add(rows);
        }
        List<List<List<Object>>> want = new ArrayList<>();
        for (JsonNode c : expected.get("containers")) {
            List<List<Object>> rows = new ArrayList<>();
            for (JsonNode p : c) {
                rows.add(List.of(p.get(0).asDouble(), p.get(1).asDouble(), p.get(2).asDouble(), p.get(3).asDouble(),
                        p.get(4).asDouble(), p.get(5).asDouble(), p.get(6).asText()));
            }
            want.add(rows);
        }
        assertThat(actual).as("vehicles and placements of %s", name).isEqualTo(want);

        Map<String, Integer> leftover = new LinkedHashMap<>();
        out.leftover().forEach(b -> leftover.merge(b.partName(), b.quantity, Integer::sum));
        Map<String, Integer> wantLeftover = new LinkedHashMap<>();
        expected.get("leftover").forEach(l -> wantLeftover.merge(l.get(0).asText(), l.get(1).asInt(), Integer::sum));
        assertThat(leftover).isEqualTo(wantLeftover);
        assertThat(out.utilization()).isCloseTo(expected.get("utilization").asDouble(),
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(out.meta().setsTotal()).isEqualTo(expected.get("meta").get("sets_total").asInt());
        assertThat(out.meta().minVehicles()).isEqualTo(expected.get("meta").get("min_containers").asInt());
        assertThat(clock.ticks()).as("constructions spent").isEqualTo(expected.get("ticks").asLong());
    }
}
