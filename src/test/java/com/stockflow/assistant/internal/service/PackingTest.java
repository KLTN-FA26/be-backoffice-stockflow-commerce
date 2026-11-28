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
    @DisplayName("the packing is the variant's one active carton (01 BR-03); none when the SKU has no carton, only a "
            + "retired one, or several, since nothing is guessed; the base unit has no row, so unit is null")
    void whichPacking() {
        assertThat(Packing.of(List.of(unit("CARTON", 50, true)))).isEqualTo(new Packing(null, "CARTON", 50));
        assertThat(Packing.of(List.of(unit("CARTON", 50, true), unit("CRATE", 200, false))))
                .as("a retired carton is never offered").isEqualTo(new Packing(null, "CARTON", 50));
        assertThat(Packing.of(List.of(unit("CARTON", 50, false)))).as("only a retired one").isEqualTo(Packing.NONE);
        assertThat(Packing.of(List.of(unit("PCS", 1, true)))).as("one piece is no carton").isEqualTo(Packing.NONE);
        assertThat(Packing.of(List.of(unit("CARTON", 50, true), unit("PALLET", 2000, true))))
                .as("two active cartons: uk_uom_conversions_one_carton forbids it, and nothing is guessed")
                .isEqualTo(Packing.NONE);
        assertThat(Packing.of(List.of())).isEqualTo(Packing.NONE);
    }

    @Test
    @DisplayName("a quantity is whole packings and loose units; nothing without a packing or below zero")
    void packed() {
        Packing carton = new Packing(null, "CARTON", 50);

        assertThat(carton.packed(1276)).isEqualTo(new Packing.Packed(25, 26));
        assertThat(carton.packed(0)).isEqualTo(new Packing.Packed(0, 0));
        assertThat(carton.packed(-3)).isNull();
        assertThat(Packing.NONE.packed(1276)).isNull();
    }

    @Test
    @DisplayName("getStockLevels gives each warehouse figure in cartons as inventory counted them, never units divided: "
            + "2 cartons of 50 and 1 of 20 are 3 cartons, not 2 and 20 loose; the company figure is asked in cartons of "
            + "the standard count; units that cannot be read leave the unit figures as they were")
    void stockLevelsInCountedCartons() {
        InventoryService inventory = mock(InventoryService.class);
        UomConversionService units = mock(UomConversionService.class);
        Sku sku = new Sku("EVAL-CUP-12OZ");
        when(inventory.itemIdsOf(anyCollection())).thenReturn(Map.of(sku.code(), UUID.randomUUID()));
        // HCM: two cartons of 50 and one of 20 - 120 units, 3 cartons; one of 50 allocated to a pick.
        when(inventory.levelsOf(sku)).thenReturn(List.of(new StockLevel(sku.code(), "HCM", 120, 120, 0, 50, 70,
                3, 3, 0, 1, 2)));
        when(inventory.availableToPromise(sku)).thenReturn(70);
        when(inventory.cartonsToPromise(Map.of(sku, 50))).thenReturn(Map.of(sku, 1));
        when(units.conversionsOf(anyCollection())).thenReturn(Map.of(sku.code(), List.of(
                unit("CARTON", 50, true))));

        GetStockLevelsTool.Answer answer = new GetStockLevelsTool(inventory, units)
                .run(ToolArguments.parse(new com.fasterxml.jackson.databind.ObjectMapper(), "{\"sku\":\"EVAL-CUP-12OZ\"}"));

        assertThat(answer.packingUnit()).isEqualTo("CARTON");
        assertThat(answer.unitsPerPacking()).isEqualTo(50);
        assertThat(answer.companyAvailableToPromise()).isEqualTo(70);
        assertThat(answer.companyCartonsToPromise()).as("cartons of 50 only: the carton of 20 never serves a line of 50")
                .isEqualTo(1);
        GetStockLevelsTool.Row hcm = answer.warehouses().getFirst();
        assertThat(hcm.onHand()).isEqualTo(120);
        assertThat(hcm.onHandCartons()).as("counted, where 120 / 50 would say 2 cartons and 20 loose").isEqualTo(3);
        assertThat(hcm.allocatedCartons()).isEqualTo(1);
        assertThat(hcm.heldOnStockCartons()).isEqualTo(1);
        assertThat(hcm.availableToPromiseCartons()).isEqualTo(2);

        when(units.conversionsOf(anyCollection())).thenThrow(new IllegalStateException("database gone"));
        GetStockLevelsTool.Answer plain = new GetStockLevelsTool(inventory, units)
                .run(ToolArguments.parse(new com.fasterxml.jackson.databind.ObjectMapper(), "{\"sku\":\"EVAL-CUP-12OZ\"}"));
        assertThat(plain.packingUnit()).isNull();
        assertThat(plain.companyCartonsToPromise()).as("no standard count, no company cartons").isNull();
        assertThat(plain.warehouses().getFirst().availableToPromise()).isEqualTo(70);
        assertThat(plain.warehouses().getFirst().availableToPromiseCartons()).isEqualTo(2);
    }

    private static UomConversion unit(String code, int factor, boolean active) {
        return new UomConversion(UUID.randomUUID(), "EVAL-CUP-12OZ", code, factor, null, null, null, null, active, 0);
    }
}
