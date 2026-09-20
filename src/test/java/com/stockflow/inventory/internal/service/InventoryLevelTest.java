package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Inventory Level is a roll-up, so the test feeds it subtotals and checks the glossary's
 * arithmetic: on-hand counts every status, available only {@code AVAILABLE}, ATP is available
 * minus reservations, and a warehouse is whatever precedes the first hyphen of a location code.
 */
class InventoryLevelTest {

    private static final Sku SKU = new Sku("SOFA-3S-GREY");

    private final StockItemRepository repository = mock(StockItemRepository.class);
    private final InventoryServiceImpl service =
            new InventoryServiceImpl(repository, mock(InventoryEventPublisher.class), Clock.systemUTC());

    private static StockLevelLine line(String location, StockStatus status, int onHand, int reserved) {
        return new StockLevelLine(new LocationId(location), status, onHand, reserved);
    }

    @Test
    @DisplayName("sums locations per warehouse; only AVAILABLE counts as available, ATP subtracts holds")
    void rollsUpPerWarehouse() {
        when(repository.findLevelLinesBySku(SKU)).thenReturn(List.of(
                line("HN-A01-2-03", StockStatus.AVAILABLE, 10, 4),
                line("HN-A01-2-04", StockStatus.AVAILABLE, 5, 0),
                line("HN-QC01", StockStatus.QUARANTINE, 7, 0),
                line("HCM-A01-1-01", StockStatus.AVAILABLE, 3, 1)));

        List<StockLevel> levels = service.levelsOf(SKU);

        assertThat(levels).extracting(StockLevel::warehouseCode).containsExactly("HCM", "HN");
        assertThat(levels.get(0)).isEqualTo(new StockLevel("SOFA-3S-GREY", "HCM", 3, 3, 1, 0, 2));
        assertThat(levels.get(1)).isEqualTo(new StockLevel("SOFA-3S-GREY", "HN", 22, 15, 4, 0, 11));
    }

    @Test
    @DisplayName("ATP of one warehouse ignores the others; an unknown warehouse promises nothing")
    void atpPerWarehouse() {
        when(repository.findLevelLinesBySku(SKU)).thenReturn(List.of(
                line("HN-A01-2-03", StockStatus.AVAILABLE, 10, 4),
                line("HCM-A01-1-01", StockStatus.AVAILABLE, 3, 1)));

        assertThat(service.availableToPromise(SKU, "hn")).isEqualTo(6);
        assertThat(service.availableToPromise(SKU, "HCM")).isEqualTo(2);
        assertThat(service.availableToPromise(SKU, "DN")).isZero();
    }

    @Test
    @DisplayName("a SKU with no stock has no levels")
    void emptyWhenNoStock() {
        when(repository.findLevelLinesBySku(SKU)).thenReturn(List.of());

        assertThat(service.levelsOf(SKU)).isEmpty();
    }

    @Test
    @DisplayName("warehouse code is the segment before the first hyphen, for bins and areas alike")
    void warehouseCodeFromLocation() {
        assertThat(new LocationId("hn-a01-2-03").warehouseCode()).isEqualTo("HN");
        assertThat(new LocationId("HN-RCV01").warehouseCode()).isEqualTo("HN");
        assertThat(new LocationId("STAGING").warehouseCode()).isEqualTo("STAGING");
    }
}
