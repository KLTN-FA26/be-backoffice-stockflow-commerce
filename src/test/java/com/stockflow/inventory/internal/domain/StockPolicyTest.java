package com.stockflow.inventory.internal.domain;
import com.stockflow.inventory.api.*;
import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class StockPolicyTest {
    @Test void nullAndZeroAreDifferentAndBothValid() {
        assertThat(StockPolicy.validate(new InventoryPolicy(null,null,RemovalStrategy.FIFO,TrackingMode.NONE,false,null)).reorderPoint()).isNull();
        assertThat(StockPolicy.validate(new InventoryPolicy(0,0,RemovalStrategy.FIFO,TrackingMode.NONE,false,null)).reorderPoint()).isZero();
    }
    @Test void invalidCombinationsAreRejected() {
        for (var p:List.of(new InventoryPolicy(-1,0,RemovalStrategy.FIFO,TrackingMode.NONE,false,null),
                new InventoryPolicy(5,6,RemovalStrategy.FIFO,TrackingMode.NONE,false,null),
                new InventoryPolicy(5,0,RemovalStrategy.FIFO,TrackingMode.LOT,true,10),
                new InventoryPolicy(5,0,RemovalStrategy.FEFO,TrackingMode.NONE,true,10),
                new InventoryPolicy(5,0,RemovalStrategy.FEFO,TrackingMode.LOT,false,10))) {
            assertThatThrownBy(() -> StockPolicy.validate(p)).isInstanceOf(BusinessException.class);
        }
    }
    private StockAllocator.Candidate lot(String location,Instant received,LocalDate expiry) {
        return new StockAllocator.Candidate(StockItemId.newId(),new LocationId(location),"L",expiry,
                StockStatus.AVAILABLE,Quantity.of(2),received);
    }
    @Test void fifoUsesReceiptNotRemainingQuantityOrLocation() {
        var newer=lot("HCM-A-01",Instant.parse("2026-09-02T00:00:00Z"),null);
        var older=lot("HCM-Z-01",Instant.parse("2026-09-01T00:00:00Z"),null);
        assertThat(StockAllocator.plan("SKU",List.of(newer,older),Quantity.of(1),RemovalStrategy.FIFO,
                LocalDate.of(2026,9,27)).getFirst().stockItemId()).isEqualTo(older.stockItemId());
    }
    @Test void expiredStockIsExcludedBeforeTheNightlySweep() {
        var expired=lot("HCM-A-01",Instant.EPOCH,LocalDate.of(2026,9,26));
        var usable=lot("HCM-B-01",Instant.EPOCH,LocalDate.of(2026,9,27));
        assertThat(StockAllocator.plan("SKU",List.of(expired,usable),Quantity.of(1),RemovalStrategy.FEFO,
                LocalDate.of(2026,9,27)).getFirst().stockItemId()).isEqualTo(usable.stockItemId());
    }
    @Test void unknownReceiptTimeCannotSilentlyBecomeFifo() {
        assertThatThrownBy(() -> StockAllocator.plan("SKU",List.of(lot("HCM-A-01",null,null)),
                Quantity.of(1),RemovalStrategy.FIFO,LocalDate.of(2026,9,27))).isInstanceOf(BusinessException.class);
    }
}
