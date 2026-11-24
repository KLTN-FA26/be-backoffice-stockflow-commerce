package com.stockflow.inventory.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 01 BR-03, BR-11: a SKU's carton is its one active packing unit of more than one piece, the default shipping
 * one among several; nothing is guessed. The rule a quote and the storefront's stock indicator both count by (5-3).
 */
class UomConversionCartonTest {

    private static final UomConversion CARTON = unit("CARTON", 50, true, false);

    @Test
    void theOnlyActivePackingUnitIsTheCarton() {
        assertThat(UomConversion.cartonOf(List.of(CARTON))).contains(CARTON);
    }

    @Test
    void aRetiredUnitOrOneOfASinglePieceIsNoCarton() {
        assertThat(UomConversion.cartonOf(List.of(unit("OLD", 100, false, false)))).isEmpty();
        assertThat(UomConversion.cartonOf(List.of(unit("SINGLE", 1, true, false)))).isEmpty();
        assertThat(UomConversion.cartonOf(List.of())).isEmpty();
    }

    @Test
    void amongSeveralTheDefaultShippingOneAndNoGuessWithoutIt() {
        UomConversion shipped = unit("CARTON", 500, true, true);
        UomConversion box = unit("BOX", 20, true, false);
        assertThat(UomConversion.cartonOf(List.of(box, shipped))).contains(shipped);
        assertThat(UomConversion.cartonOf(List.of(box, CARTON))).as("two, neither shipped by default").isEmpty();
    }

    private static UomConversion unit(String code, int factor, boolean active, boolean defaultShipping) {
        return new UomConversion(UUID.randomUUID(), "LY12-ACME-01", code, factor, BigDecimal.ONE, null, null, null,
                active, 0, defaultShipping);
    }
}
