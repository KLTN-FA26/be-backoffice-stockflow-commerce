package com.stockflow.inventory.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whole cartons of the hold's count, FIFO, k &lt; N (kltn-docs 07 BR-03, 20 §5.6; team plan 4C-2; ADR-0011) are
 * business rules, so they get business-rule tests. The planner works on {@link StockAllocator.Candidate} projections, which also makes it trivial
 * to test: a candidate is a handful of values, with no aggregate to build and no database to reach.
 */
class StockAllocatorTest {

    private static final Instant DAY_1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant DAY_2 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant DAY_3 = Instant.parse("2026-10-03T00:00:00Z");

    /** A free carton of {@code units}: the planner is only ever offered cartons no order holds. */
    private static StockAllocator.Candidate carton(String location, Instant received, int units) {
        return new StockAllocator.Candidate(new StockPackageId(UUID.randomUUID()), new LocationId(location),
                "LOT-" + location, StockStatus.AVAILABLE, units, received);
    }

    /** {@code cartons} cartons of {@code unitsPerCarton}, as a hold asks for them. */
    private static StockAllocator.Plan plan(List<StockAllocator.Candidate> pool, int cartons, int unitsPerCarton) {
        return StockAllocator.plan("CUP-PET-500", pool, cartons, unitsPerCarton);
    }

    @Test
    @DisplayName("N cartons, received first, first out (07 BR-03.3); each line is one whole carton")
    void wholeCartonsFirstReceivedFirst() {
        var third = carton("HCM-A-01", DAY_3, 1000);
        var first = carton("HCM-B-02", DAY_1, 1000);
        var second = carton("HCM-A-02", DAY_2, 1000);

        StockAllocator.Plan plan = plan(List.of(third, first, second), 2, 1000);

        assertThat(plan.isShort()).isFalse();
        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(first.stockPackageId(), second.stockPackageId());
        assertThat(plan.lines()).allSatisfy(line -> assertThat(line.quantity().value()).isEqualTo(1000));
    }

    @Test
    @DisplayName("k < N: the k cartons there are, and N - k cartons short (07 BR-03.4); complete() refuses it")
    void shortOfCartons() {
        var one = carton("HCM-A-01", DAY_1, 1000);
        var two = carton("HCM-A-02", DAY_2, 1000);

        StockAllocator.Plan plan = plan(List.of(two, one), 3, 1000);

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(one.stockPackageId(), two.stockPackageId());
        assertThat(plan.planned()).isEqualTo(2000);
        assertThat(plan.shortCartons()).isEqualTo(1);
        assertThat(plan.isShort()).isTrue();
        assertThatThrownBy(plan::complete).isInstanceOfSatisfying(InsufficientStockException.class, e -> {
            assertThat(e.requested()).isEqualTo(3000);
            assertThat(e.available()).isEqualTo(2000);
        });
    }

    @Test
    @DisplayName("ADR-0011: a hold is served by cartons of its count only - a carton of another count is no candidate")
    void cartonsOfAnotherCountAreNotCandidates() {
        var big = carton("HCM-A-01", DAY_1, 1000);
        var smallOlder = carton("HCM-A-02", DAY_1, 500);
        var smallNewer = carton("HCM-A-03", DAY_2, 500);
        List<StockAllocator.Candidate> pool = List.of(big, smallNewer, smallOlder);

        // Cartons of 500: the older carton of 1000 is not taken ahead of them, and is not broken for them.
        assertThat(plan(pool, 2, 500).lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(smallOlder.stockPackageId(), smallNewer.stockPackageId());
        assertThat(plan(List.of(big), 1, 500).lines()).as("a larger carton is never broken to fill the gap").isEmpty();

        // Cartons of 1000: two cartons of 500 hold the same units and still do not make one up.
        StockAllocator.Plan thousands = plan(pool, 2, 1000);
        assertThat(thousands.lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(big.stockPackageId());
        assertThat(thousands.shortCartons()).isEqualTo(1);
        assertThat(thousands.planned()).isEqualTo(1000);
        assertThat(thousands.required()).isEqualTo(2000);
        assertThat(plan(List.of(smallOlder, smallNewer), 1, 1000).lines())
                .as("smaller cartons are not added up into a larger one").isEmpty();
    }

    @Test
    @DisplayName("a plan is for at least one carton of at least one unit")
    void aPlanIsForCartons() {
        assertThatThrownBy(() -> plan(List.of(), 0, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(List.of(), 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a carton that is not sellable is not stock to give, whatever its count")
    void unusableCartonsAreSkipped() {
        var quarantined = new StockAllocator.Candidate(new StockPackageId(UUID.randomUUID()),
                new LocationId("HCM-QC-01"), "L", StockStatus.QUARANTINE, 10, DAY_1);
        var good = carton("HCM-A-03", DAY_2, 10);

        StockAllocator.Plan plan = plan(List.of(quarantined, good), 2, 10);

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(good.stockPackageId());
        assertThat(quarantined.available().value()).as("it would give nothing").isZero();
        assertThat(good.available().value()).isEqualTo(10);
    }

    @Test
    @DisplayName("FIFO for every SKU: no expiry orders cartons (kltn-docs 01 BR-17), the receipt time does")
    void fifoForEverySku() {
        var older = carton("HCM-A-02", DAY_1, 10);
        var newer = carton("HCM-A-01", DAY_3, 10);

        StockAllocator.Plan plan = StockAllocator.plan("INK", List.of(newer, older), 1, 10);

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(older.stockPackageId());
    }

    @Test
    @DisplayName("a carton of no receipt time goes after every timed one; it is still given when nothing else is left")
    void undatedCartonsGoLast() {
        var undated = carton("HCM-A-01", null, 10);
        var dated = carton("HCM-Z-01", DAY_3, 10);

        assertThat(plan(List.of(undated, dated), 1, 10).lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(dated.stockPackageId());
        assertThat(plan(List.of(undated, dated), 2, 10).lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(dated.stockPackageId(), undated.stockPackageId());
    }

    @Test
    @DisplayName("ties on the receipt time break on location and id: the plan is deterministic")
    void deterministic() {
        var b = carton("HCM-B-01", DAY_1, 10);
        var a = carton("HCM-A-01", DAY_1, 10);

        assertThat(plan(List.of(b, a), 1, 10).lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(a.stockPackageId());
        assertThat(plan(List.of(a, b), 1, 10).lines()).extracting(StockAllocator.AllocationLine::stockPackageId)
                .containsExactly(a.stockPackageId());
    }

    @Test
    @DisplayName("a candidate is a carton: it has an id, a place, a status and a count of at least one unit")
    void aCandidateIsACarton() {
        assertThatThrownBy(() -> new StockAllocator.Candidate(null, new LocationId("HCM-A-01"), "L",
                StockStatus.AVAILABLE, 1, DAY_1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StockAllocator.Candidate(new StockPackageId(UUID.randomUUID()),
                new LocationId("HCM-A-01"), "L", StockStatus.AVAILABLE, 0, DAY_1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
