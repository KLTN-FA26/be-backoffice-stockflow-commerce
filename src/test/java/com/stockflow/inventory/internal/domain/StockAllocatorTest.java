package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.RemovalStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whole cartons, FIFO, k &lt; N (kltn-docs 07 BR-03, 20 §5.6; team plan 4C-2) are business rules, so they get
 * business-rule tests. The planner works on {@link StockAllocator.Candidate} projections, which also makes it trivial
 * to test: a candidate is a handful of values, with no aggregate to build and no database to reach.
 */
class StockAllocatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final Instant DAY_1 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant DAY_2 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant DAY_3 = Instant.parse("2026-10-03T00:00:00Z");

    /** A sealed carton of {@code units}, nothing of it held. */
    private static StockAllocator.Candidate carton(String location, Instant received, int units) {
        return new StockAllocator.Candidate(StockItemId.newId(), new LocationId(location), "LOT-" + location, null,
                StockStatus.AVAILABLE, Quantity.of(units), received, UUID.randomUUID(), true);
    }

    private static StockAllocator.Plan plan(List<StockAllocator.Candidate> pool, int units) {
        return StockAllocator.plan("CUP-PET-500", pool, Quantity.of(units), RemovalStrategy.FIFO, TODAY);
    }

    @Test
    @DisplayName("N cartons, received first, first out (07 BR-03.3); each line is one whole carton")
    void wholeCartonsFirstReceivedFirst() {
        var third = carton("HCM-A-01", DAY_3, 1000);
        var first = carton("HCM-B-02", DAY_1, 1000);
        var second = carton("HCM-A-02", DAY_2, 1000);

        StockAllocator.Plan plan = plan(List.of(third, first, second), 2000);

        assertThat(plan.isShort()).isFalse();
        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockItemId)
                .containsExactly(first.stockItemId(), second.stockItemId());
        assertThat(plan.lines()).allSatisfy(line -> assertThat(line.quantity().value()).isEqualTo(1000));
    }

    @Test
    @DisplayName("k < N: the k cartons there are, and N - k cartons short (07 BR-03.4); complete() refuses it")
    void shortOfCartons() {
        var one = carton("HCM-A-01", DAY_1, 1000);
        var two = carton("HCM-A-02", DAY_2, 1000);

        StockAllocator.Plan plan = plan(List.of(two, one), 3000);

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockItemId)
                .containsExactly(one.stockItemId(), two.stockItemId());
        assertThat(plan.planned()).isEqualTo(2000);
        assertThat(plan.shortUnits()).isEqualTo(1000);
        assertThat(plan.isShort()).isTrue();
        assertThatThrownBy(plan::complete).isInstanceOfSatisfying(InsufficientStockException.class, e -> {
            assertThat(e.requested()).isEqualTo(3000);
            assertThat(e.available()).isEqualTo(2000);
        });
    }

    @Test
    @DisplayName("loose units, an opened carton and a carton another order holds part of are never drawn on")
    void onlySealedUnheldCartons() {
        var loose = new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-A-01"), "L", null,
                StockStatus.AVAILABLE, Quantity.of(5000), DAY_1);
        var partOfACarton = new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-A-02"), "L", null,
                StockStatus.AVAILABLE, Quantity.of(400), DAY_1, UUID.randomUUID(), false);
        var whole = carton("HCM-A-03", DAY_3, 1000);

        StockAllocator.Plan plan = plan(List.of(loose, partOfACarton, whole), 2000);

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockItemId)
                .containsExactly(whole.stockItemId());
        assertThat(plan.shortUnits()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a carton is never broken: one larger than what is still needed is skipped, and the gap stays short")
    void noCartonIsBroken() {
        var big = carton("HCM-A-01", DAY_1, 1000);

        StockAllocator.Plan plan = plan(List.of(big), 500);

        assertThat(plan.lines()).isEmpty();
        assertThat(plan.shortUnits()).isEqualTo(500);
    }

    @Test
    @DisplayName("blocked, quarantined, expired and empty cartons are not stock to give")
    void unusableCartonsAreSkipped() {
        var quarantined = new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-QC-01"), "L", null,
                StockStatus.QUARANTINE, Quantity.of(10), DAY_1, UUID.randomUUID(), true);
        var expired = new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-A-01"), "L",
                TODAY.minusDays(1), StockStatus.AVAILABLE, Quantity.of(10), DAY_1, UUID.randomUUID(), true);
        var empty = new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-A-02"), "L", null,
                StockStatus.AVAILABLE, Quantity.of(0), DAY_1, UUID.randomUUID(), true);
        var good = carton("HCM-A-03", DAY_2, 10);

        StockAllocator.Plan plan = plan(List.of(quarantined, expired, empty, good), 20);

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockItemId)
                .containsExactly(good.stockItemId());
    }

    @Test
    @DisplayName("a FEFO SKU gives the carton that expires first, then the one received first")
    void fefoWhereThePolicySaysSo() {
        var later = new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-A-01"), "L1",
                TODAY.plusDays(60), StockStatus.AVAILABLE, Quantity.of(10), DAY_1, UUID.randomUUID(), true);
        var sooner = new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-A-02"), "L2",
                TODAY.plusDays(30), StockStatus.AVAILABLE, Quantity.of(10), DAY_3, UUID.randomUUID(), true);

        StockAllocator.Plan plan = StockAllocator.plan("INK", List.of(later, sooner), Quantity.of(10),
                RemovalStrategy.FEFO, TODAY);

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockItemId)
                .containsExactly(sooner.stockItemId());
    }

    @Test
    @DisplayName("a FIFO SKU with a carton of no receipt time cannot be ordered: INVENTORY_POLICY_STOCK_CONFLICT")
    void fifoNeedsReceiptTimes() {
        var undated = carton("HCM-A-01", null, 10);

        assertThatThrownBy(() -> plan(List.of(undated), 10)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT));
    }

    @Test
    @DisplayName("ties on the receipt time break on location and id: the plan is deterministic")
    void deterministic() {
        var b = carton("HCM-B-01", DAY_1, 10);
        var a = carton("HCM-A-01", DAY_1, 10);

        assertThat(plan(List.of(b, a), 10).lines()).extracting(StockAllocator.AllocationLine::stockItemId)
                .containsExactly(a.stockItemId());
        assertThat(plan(List.of(a, b), 10).lines()).extracting(StockAllocator.AllocationLine::stockItemId)
                .containsExactly(a.stockItemId());
    }

    @Test
    @DisplayName("only a row in a carton can be a whole carton")
    void wholeCartonNeedsACarton() {
        assertThatThrownBy(() -> new StockAllocator.Candidate(StockItemId.newId(), new LocationId("HCM-A-01"), "L",
                null, StockStatus.AVAILABLE, Quantity.of(1), DAY_1, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
