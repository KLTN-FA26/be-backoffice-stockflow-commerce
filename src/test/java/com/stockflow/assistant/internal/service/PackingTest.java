package com.stockflow.assistant.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A stock figure in the SKU's carton, divided here and never by the model. */
class PackingTest {

    @Test
    @DisplayName("the packing is the shipping one, else the purchase one, else the smallest; none when the SKU ships "
            + "loose; never the base unit or a retired one")
    void whichPacking() {
        assertThat(Packing.of(List.of(unit("PCS", 1, true, false, false, true), unit("BOX", 10, false, true, false,
                true), unit("CARTON", 50, false, false, true, true)))).isEqualTo(new Packing("PCS", "CARTON", 50));
        assertThat(Packing.of(List.of(unit("PCS", 1, true, false, false, true), unit("BOX", 10, false, true, false,
                true), unit("PALLET", 1000, false, false, false, true)))).isEqualTo(new Packing("PCS", "BOX", 10));
        assertThat(Packing.of(List.of(unit("PCS", 1, true, false, false, true), unit("BOX", 10, false, false, false,
                true), unit("CRATE", 200, false, false, false, false)))).isEqualTo(new Packing("PCS", "BOX", 10));
        assertThat(Packing.of(List.of(unit("PCS", 1, true, true, true, true)))).isEqualTo(new Packing("PCS", null,
                null));
        assertThat(Packing.of(List.of(unit("PCS", 1, true, false, false, true), unit("CARTON", 50, false, false, false,
                true), unit("PALLET", 2000, false, false, false, true)))).isEqualTo(new Packing("PCS", "CARTON", 50));
        assertThat(Packing.of(List.of(unit("PCS", 1, true, false, true, true), unit("CARTON", 50, false, true, false,
                true)))).as("shipped loose").isEqualTo(new Packing("PCS", null, null));
    }

    @Test
    @DisplayName("a quantity is whole packings and loose units; nothing without a packing or below zero")
    void packed() {
        Packing carton = new Packing("PCS", "CARTON", 50);

        assertThat(carton.packed(1276)).isEqualTo(new Packing.Packed(25, 26));
        assertThat(carton.packed(0)).isEqualTo(new Packing.Packed(0, 0));
        assertThat(carton.packed(-3)).isNull();
        assertThat(Packing.NONE.packed(1276)).isNull();
    }

    @Test
    @DisplayName("getStockLevels gives the unit, the carton and each figure in cartons; units that cannot be read "
            + "leave the figures as they were")
    void stockLevelsInCartons() {
        InventoryService inventory = mock(InventoryService.class);
        UomConversionService units = mock(UomConversionService.class);
        Sku sku = new Sku("EVAL-CUP-12OZ");
        when(inventory.itemIdsOf(anyCollection())).thenReturn(Map.of(sku.code(), UUID.randomUUID()));
        when(inventory.levelsOf(sku)).thenReturn(List.of(new StockLevel(sku.code(), "HCM", 1276, 1276, 0, 0, 1276)));
        when(inventory.availableToPromise(sku)).thenReturn(1276);
        when(units.conversionsOf(anyCollection())).thenReturn(Map.of(sku.code(), List.of(
                unit("PCS", 1, true, false, false, true), unit("CARTON", 50, false, true, true, true))));

        GetStockLevelsTool.Answer answer = new GetStockLevelsTool(inventory, units)
                .run(ToolArguments.parse(new com.fasterxml.jackson.databind.ObjectMapper(), "{\"sku\":\"EVAL-CUP-12OZ\"}"));

        assertThat(answer.unit()).isEqualTo("PCS");
        assertThat(answer.packingUnit()).isEqualTo("CARTON");
        assertThat(answer.companyAvailableToPromisePacked()).isEqualTo(new Packing.Packed(25, 26));
        assertThat(answer.warehouses().getFirst().availableToPromisePacked()).isEqualTo(new Packing.Packed(25, 26));

        when(units.conversionsOf(anyCollection())).thenThrow(new IllegalStateException("database gone"));
        GetStockLevelsTool.Answer plain = new GetStockLevelsTool(inventory, units)
                .run(ToolArguments.parse(new com.fasterxml.jackson.databind.ObjectMapper(), "{\"sku\":\"EVAL-CUP-12OZ\"}"));
        assertThat(plain.unit()).isNull();
        assertThat(plain.warehouses().getFirst().availableToPromise()).isEqualTo(1276);
    }

    private static UomConversion unit(String code, int factor, boolean base, boolean purchase, boolean shipping,
                                      boolean active) {
        return new UomConversion(UUID.randomUUID(), "EVAL-CUP-12OZ", code, factor, base, null, null, null, null,
                purchase, active, 0, shipping);
    }
}
