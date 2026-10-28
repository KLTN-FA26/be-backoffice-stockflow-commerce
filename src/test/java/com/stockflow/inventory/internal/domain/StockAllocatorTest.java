package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEFO is a business rule, so it gets business-rule tests.
 *
 * <p>The planner works on {@link StockAllocator.Candidate} projections, which also makes it trivial
 * to test: a candidate is five values, with no aggregate to build and no database to reach.</p>
 */
class StockAllocatorTest {

    private static final Sku SKU = new Sku("TABLE-OAK-160");

    private static StockAllocator.Candidate lot(String location, LocalDate expiry, int available) {
        return new StockAllocator.Candidate(StockItemId.newId(), new LocationId(location),
                "LOT-" + location, expiry, StockStatus.AVAILABLE, Quantity.of(available));
    }

    private static StockAllocator.Candidate quarantined(String location, int available) {
        return new StockAllocator.Candidate(StockItemId.newId(), new LocationId(location),
                "LOT-Q", null, StockStatus.QUARANTINE, Quantity.of(available));
    }

    @Test
    @DisplayName("draws from the lot that expires first")
    void allocatesEarliestExpiryFirst() {
        var later = lot("HCM-B-02", LocalDate.of(2027, 6, 30), 10);
        var sooner = lot("HCM-B-01", LocalDate.of(2027, 1, 31), 10);

        var plan = StockAllocator.plan(SKU.code(), List.of(later, sooner), Quantity.of(4));

        assertThat(plan).singleElement()
                .extracting(line -> line.candidate().location().code())
                .isEqualTo("HCM-B-01");
    }

    @Test
    @DisplayName("spans lots when one is not enough, still oldest first")
    void splitsAcrossLotsInFefoOrder() {
        var sooner = lot("HCM-B-01", LocalDate.of(2027, 1, 31), 3);
        var later = lot("HCM-B-02", LocalDate.of(2027, 6, 30), 10);

        var plan = StockAllocator.plan(SKU.code(), List.of(later, sooner), Quantity.of(7));

        assertThat(plan).hasSize(2);
        assertThat(plan.get(0).candidate().location().code()).isEqualTo("HCM-B-01");
        assertThat(plan.get(0).quantity()).isEqualTo(Quantity.of(3));
        assertThat(plan.get(1).quantity()).isEqualTo(Quantity.of(4));
    }

    @Test
    @DisplayName("undated stock is used only after dated stock")
    void datedStockGoesBeforeUndated() {
        var undated = lot("HCM-B-03", null, 10);
        var dated = lot("HCM-B-01", LocalDate.of(2027, 1, 31), 10);

        var plan = StockAllocator.plan(SKU.code(), List.of(undated, dated), Quantity.of(2));

        assertThat(plan).singleElement()
                .extracting(line -> line.candidate().location().code())
                .isEqualTo("HCM-B-01");
    }

    @Test
    @DisplayName("quarantined lots are invisible to the planner")
    void skipsNonSellableStock() {
        var blocked = quarantined("HCM-QC-01", 50);
        var sellable = lot("HCM-B-01", LocalDate.of(2027, 6, 30), 5);

        assertThatThrownBy(() ->
                StockAllocator.plan(SKU.code(), List.of(blocked, sellable), Quantity.of(10)))
                .isInstanceOf(InsufficientStockException.class)
                // 5, not 55: the 50 quarantined units do not count towards what we can promise.
                .hasMessageContaining("available 5");
    }

    @Test
    @DisplayName("the plan is deterministic when two lots are otherwise identical")
    void tiesBreakOnLocationSoThePlanIsStable() {
        var b = lot("HCM-B-02", LocalDate.of(2027, 1, 31), 5);
        var a = lot("HCM-B-01", LocalDate.of(2027, 1, 31), 5);

        var first = StockAllocator.plan(SKU.code(), List.of(b, a), Quantity.of(3));
        var second = StockAllocator.plan(SKU.code(), List.of(a, b), Quantity.of(3));

        assertThat(first.get(0).candidate().location().code()).isEqualTo("HCM-B-01");
        assertThat(second.get(0).candidate().location().code()).isEqualTo("HCM-B-01");
    }

    @Test
    @DisplayName("every plan line names the stock item to lock")
    void planLinesCarryTheStockItemId() {
        UUID id = UUID.randomUUID();
        var only = new StockAllocator.Candidate(new StockItemId(id), new LocationId("HCM-B-01"),
                null, null, StockStatus.AVAILABLE, Quantity.of(10));

        var plan = StockAllocator.plan(SKU.code(), List.of(only), Quantity.of(4));

        assertThat(plan).singleElement()
                .extracting(line -> line.stockItemId().value())
                .isEqualTo(id);
    }

    /**
     * SCRUM-473: inside the group where the need runs out, whole cartons go for the round part and the remainder comes
     * from opened cartons, then loose units, and only then from a carton broken for it.
     */
    @Nested
    @DisplayName("cartons, inside the last FEFO/FIFO group")
    class CartonOrder {

        private static final LocalDate EXPIRY = LocalDate.of(2027, 1, 31);
        private static final java.time.Instant T1 = java.time.Instant.parse("2026-01-01T00:00:00Z");
        private static final java.time.Instant T2 = java.time.Instant.parse("2026-02-01T00:00:00Z");
        private final Map<StockItemId, String> names = new HashMap<>();

        private StockAllocator.Candidate candidate(String name, LocalDate expiry, java.time.Instant receivedAt,
                                                   int available, UUID carton, boolean whole) {
            var c = new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-A01-1-A"), "LOT-1", expiry,
                    StockStatus.AVAILABLE, Quantity.of(available), receivedAt, carton, whole);
            names.put(c.stockItemId(), name);
            return c;
        }

        private StockAllocator.Candidate whole(String name, int quantity) {
            return candidate(name, EXPIRY, T1, quantity, UUID.randomUUID(), true);
        }

        private StockAllocator.Candidate opened(String name, int available) {
            return candidate(name, EXPIRY, T1, available, UUID.randomUUID(), false);
        }

        private StockAllocator.Candidate loose(String name, int available) {
            return candidate(name, EXPIRY, T1, available, null, false);
        }

        private List<String> plan(int required, StockAllocator.Candidate... candidates) {
            return describe(StockAllocator.plan(SKU.code(), List.of(candidates), Quantity.of(required)));
        }

        private List<String> describe(List<StockAllocator.AllocationLine> plan) {
            return plan.stream().map(l -> names.get(l.stockItemId()) + ":" + l.quantity().value()).toList();
        }

        @Test
        @DisplayName("whole cartons for the round part, the rest from an opened one; no third carton is broken")
        void wholeCartonsForTheRoundPart() {
            // which two of three equal cartons is immaterial; that none is broken is the point
            List<String> plan = plan(2300, whole("w1", 1000), whole("w2", 1000), whole("w3", 1000), opened("o", 400));
            assertThat(plan).hasSize(3).contains("o:300");
            assertThat(plan).filteredOn(line -> line.startsWith("w")).allMatch(line -> line.endsWith(":1000"));
        }

        @Test
        @DisplayName("the largest whole carton first, so cartons counted short still fit whole")
        void largestWholeCartonFirst() {
            // smallest first would take 980 and 990 whole and break the 1000 for the last 20
            assertThat(plan(1990, whole("short", 980), whole("mid", 990), whole("full", 1000)))
                    .containsExactlyInAnyOrder("full:1000", "mid:990");
        }

        @Test
        @DisplayName("the remainder comes from an opened carton before loose units")
        void remainderFromOpenedBeforeLoose() {
            assertThat(plan(40, loose("l", 50), opened("o", 80))).containsExactly("o:40");
        }

        @Test
        @DisplayName("the remainder comes from loose units before a sealed carton is broken")
        void remainderFromLooseBeforeBreakingACarton() {
            assertThat(plan(20, whole("w", 1000), loose("l", 1500))).containsExactly("l:20");
        }

        @Test
        @DisplayName("with nothing else to draw on, the smallest whole carton is broken")
        void breaksTheSmallestWholeCartonWhenNothingElseCovers() {
            assertThat(plan(500, whole("full", 1000), whole("short", 980))).containsExactly("short:500");
        }

        @Test
        @DisplayName("opened cartons are drained smallest first")
        void openedCartonsSmallestFirst() {
            assertThat(plan(50, opened("big", 70), opened("small", 20))).containsExactly("small:20", "big:30");
        }

        @Test
        @DisplayName("a sealed carton another order holds part of counts as opened: it will be opened anyway")
        void partlyHeldSealedCartonCountsAsOpened() {
            // a carton of 2000 counted in, 800 of it held by another order
            assertThat(plan(300, whole("w", 1000), opened("partlyHeld", 1200))).containsExactly("partlyHeld:300");
        }

        @Test
        @DisplayName("FEFO comes first: an earlier-expiring opened carton before a later whole one")
        void fefoBeatsCartonPreference() {
            var earlier = candidate("earlier", LocalDate.of(2026, 12, 1), T1, 300, UUID.randomUUID(), false);
            var later = candidate("later", LocalDate.of(2027, 1, 1), T1, 1000, UUID.randomUUID(), true);

            assertThat(describe(StockAllocator.plan(SKU.code(), List.of(later, earlier), Quantity.of(1000),
                    com.stockflow.inventory.api.RemovalStrategy.FEFO, LocalDate.of(2026, 10, 28))))
                    .containsExactly("earlier:300", "later:700");
        }

        @Test
        @DisplayName("under FIFO the groups are receipts: the older loose units, then the newer carton broken")
        void fifoGroupsByReceivedAt() {
            var newer = candidate("newer", null, T2, 10, UUID.randomUUID(), true);
            var older = candidate("older", null, T1, 4, null, false);

            assertThat(describe(StockAllocator.plan(SKU.code(), List.of(newer, older), Quantity.of(10),
                    com.stockflow.inventory.api.RemovalStrategy.FIFO, LocalDate.of(2026, 10, 28))))
                    .containsExactly("older:4", "newer:6");
        }

        @Test
        @DisplayName("loose stock only plans as before: the smaller remainder first")
        void looseOnlyPlansAsBefore() {
            assertThat(plan(8, loose("five", 5), loose("two", 2), loose("nine", 9)))
                    .containsExactly("two:2", "five:5", "nine:1");
        }

        @Test
        @DisplayName("a whole carton must name its carton")
        void wholeNeedsACarton() {
            assertThatThrownBy(() -> candidate("x", EXPIRY, T1, 5, null, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
