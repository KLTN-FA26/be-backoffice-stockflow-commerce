package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.api.StockAvailability;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.service.StockBrowsing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** kltn-docs 01 BR-09: stock goes out in pieces and again in cartons of the SKU's carton unit, never rounded. */
class StockInCartonsTest {

    private final InventoryWebMapper mapper = new InventoryWebMapperImpl();

    @Test
    @DisplayName("pieces become cartons only when the division is exact and the SKU has a carton unit")
    void cartonCount() {
        assertThat(CartonCount.of(3000, 1000)).isEqualTo(3);
        assertThat(CartonCount.of(0, 1000)).isZero();
        assertThat(CartonCount.of(1500, 1000)).isNull();
        assertThat(CartonCount.of(3000, null)).isNull();
        assertThat(CartonCount.of(3000, 0)).isNull();
        assertThat(CartonCount.of(null, 1000)).isNull();
    }

    @Test
    @DisplayName("a stock level carries every quantity in cartons beside the pieces")
    void level() {
        var response = mapper.toLevelResponses(List.of(new StockLevel("CUP", "HCM", 5000, 4000, 1000, 2000, 1000)),
                1000).getFirst();

        assertThat(response.unitsPerCarton()).isEqualTo(1000);
        assertThat(response.onHand()).isEqualTo(5000);
        assertThat(response.onHandCartons()).isEqualTo(5);
        assertThat(response.availableCartons()).isEqualTo(4);
        assertThat(response.reservedCartons()).isEqualTo(1);
        assertThat(response.allocatedCartons()).isEqualTo(2);
        assertThat(response.atpCartons()).isEqualTo(1);
    }

    @Test
    @DisplayName("a SKU with no carton unit has pieces only")
    void levelWithoutCartonUnit() {
        var response = mapper.toResponse(new StockLevel("CUP", "HCM", 5000, 4000, 0, 0, 4000), null);

        assertThat(response.unitsPerCarton()).isNull();
        assertThat(response.onHand()).isEqualTo(5000);
        assertThat(response.onHandCartons()).isNull();
        assertThat(response.atpCartons()).isNull();
    }

    @Test
    @DisplayName("a stock row is one carton; hidden from a blind counter it loses the cartons with the pieces, "
            + "and keeps the carton's standard count")
    void stockRowAndBlindCount() {
        var row = mapper.toResponse(new StockAvailability(UUID.randomUUID(), "CUP", "HCM-A01-1-A", "L1", 1000, 0, 0,
                1000), 1000);

        assertThat(row.onHandCartons()).isEqualTo(1);
        assertThat(row.reservedCartons()).isZero();
        assertThat(row.availableCartons()).isEqualTo(1);

        var hidden = row.hidden();
        assertThat(hidden.onHand()).isNull();
        assertThat(hidden.onHandCartons()).isNull();
        assertThat(hidden.reservedCartons()).isNull();
        assertThat(hidden.availableCartons()).isNull();
        assertThat(hidden.unitsPerCarton()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a warehouse's stock row: cartons from its own carton count; a withheld row has none")
    void warehouseRow() {
        var row = new StockBrowsing.StockRow(UUID.randomUUID(), "CUP", "HCM-A01-1-A", "L1", 1000, 1000, 1000, 0,
                StockStatus.AVAILABLE, Instant.EPOCH, false);

        var shown = mapper.toResponse(row);
        assertThat(shown.onHandCartons()).isEqualTo(1);
        assertThat(shown.reservedCartons()).isEqualTo(1);
        assertThat(shown.availableCartons()).isZero();
        assertThat(shown.status()).isEqualTo("AVAILABLE");

        var withheld = mapper.toResponse(row.hidden());
        assertThat(withheld.hiddenDuringCount()).isTrue();
        assertThat(withheld.onHandCartons()).isNull();
        assertThat(withheld.reservedCartons()).isNull();
        assertThat(withheld.availableCartons()).isNull();
        assertThat(withheld.unitsPerCarton()).isEqualTo(1000);
    }
}
