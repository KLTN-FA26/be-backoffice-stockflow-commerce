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

/** kltn-docs 01 BR-09: stock goes out in pieces and again in cartons, counted from the cartons (ADR-0011, 3e). */
class StockInCartonsTest {

    private final InventoryWebMapper mapper = new InventoryWebMapperImpl();

    @Test
    @DisplayName("a one-carton row is one carton while it holds units, none at zero, unknown while withheld")
    void cartonCount() {
        assertThat(CartonCount.ofOneCarton(1000)).isEqualTo(1);
        assertThat(CartonCount.ofOneCarton(0)).isZero();
        assertThat(CartonCount.ofOneCarton(null)).isNull();
    }

    @Test
    @DisplayName("a stock level carries the cartons it counted beside the pieces")
    void level() {
        var response = mapper.toLevelResponses(List.of(new StockLevel("CUP", "HCM", 5000, 4000, 1000, 2000, 1000,
                5, 4, 1, 2, 1)), 1000).getFirst();

        assertThat(response.unitsPerCarton()).isEqualTo(1000);
        assertThat(response.onHand()).isEqualTo(5000);
        assertThat(response.onHandCartons()).isEqualTo(5);
        assertThat(response.availableCartons()).isEqualTo(4);
        assertThat(response.reservedCartons()).isEqualTo(1);
        assertThat(response.allocatedCartons()).isEqualTo(2);
        assertThat(response.atpCartons()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0011 3e: cartons of two counts, or no carton unit, still have their cartons")
    void levelWithoutCartonUnit() {
        var response = mapper.toResponse(new StockLevel("CUP", "HCM", 1500, 1500, 0, 0, 1500, 2, 2, 0, 0, 2), null);

        assertThat(response.unitsPerCarton()).isNull();
        assertThat(response.onHand()).isEqualTo(1500);
        assertThat(response.onHandCartons()).isEqualTo(2);
        assertThat(response.atpCartons()).isEqualTo(2);
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
