package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
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
}
