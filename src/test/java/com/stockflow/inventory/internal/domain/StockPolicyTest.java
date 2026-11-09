package com.stockflow.inventory.internal.domain;
import com.stockflow.inventory.api.*;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class StockPolicyTest {
    @Test void aPolicyNamesItsStrategyAndTracking() {
        var policy = new InventoryPolicy(RemovalStrategy.FIFO, TrackingMode.LOT);
        assertThat(StockPolicy.validate(policy)).isSameAs(policy);
    }
    @Test void aPolicyWithoutStrategyOrTrackingIsRefused() {
        for (var p : java.util.Arrays.asList(new InventoryPolicy(null, TrackingMode.NONE),
                new InventoryPolicy(RemovalStrategy.FIFO, null), null)) {
            assertThatThrownBy(() -> StockPolicy.validate(p))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVENTORY_POLICY_INVALID));
        }
    }
    private StockAllocator.Candidate lot(String location, Instant received) {
        return new StockAllocator.Candidate(StockItemId.newId(), new LocationId(location), "L", StockStatus.AVAILABLE,
                Quantity.of(2), received, java.util.UUID.randomUUID(), true);
    }
    @Test void fifoUsesReceiptNotRemainingQuantityOrLocation() {
        var newer = lot("HCM-A-01", Instant.parse("2026-09-02T00:00:00Z"));
        var older = lot("HCM-Z-01", Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(StockAllocator.plan("SKU", List.of(newer, older), Quantity.of(2)).lines().getFirst().stockItemId())
                .isEqualTo(older.stockItemId());
    }
    @Test void unknownReceiptTimeGoesAfterEveryTimedCarton() {
        // Stock older than timed receipts sorts last, as it did before FEFO went: no SKU's order changes under it.
        var untimed = lot("HCM-A-01", null);
        var timed = lot("HCM-Z-01", Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(StockAllocator.plan("SKU", List.of(untimed, timed), Quantity.of(2)).lines().getFirst().stockItemId())
                .isEqualTo(timed.stockItemId());
    }
}
