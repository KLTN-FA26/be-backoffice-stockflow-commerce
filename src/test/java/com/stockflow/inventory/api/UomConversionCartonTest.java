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

    private static final UomConversion PIECE = unit("PCS", 1, true, true, false);
    private static final UomConversion CARTON = unit("CARTON", 50, false, true, false);

    @Test
    void theOnlyActivePackingUnitIsTheCarton() {
        assertThat(UomConversion.cartonOf(List.of(PIECE, CARTON))).contains(CARTON);
    }

    @Test
    void aRetiredUnitOrOneOfASinglePieceIsNoCarton() {
        assertThat(UomConversion.cartonOf(List.of(PIECE, unit("OLD", 100, false, false, false)))).isEmpty();
        assertThat(UomConversion.cartonOf(List.of(PIECE, unit("EACH", 1, false, true, false)))).isEmpty();
        assertThat(UomConversion.cartonOf(List.of(PIECE))).isEmpty();
        assertThat(UomConversion.cartonOf(List.of())).isEmpty();
    }

    @Test
    void amongSeveralTheDefaultShippingOneAndNoGuessWithoutIt() {
        UomConversion shipped = unit("CARTON", 500, false, true, true);
        UomConversion box = unit("BOX", 20, false, true, false);
        assertThat(UomConversion.cartonOf(List.of(PIECE, box, shipped))).contains(shipped);
        assertThat(UomConversion.cartonOf(List.of(PIECE, box, CARTON))).as("two, neither shipped by default").isEmpty();
    }

    private static UomConversion unit(String code, int factor, boolean base, boolean active, boolean defaultShipping) {
        return new UomConversion(UUID.randomUUID(), "LY12-ACME-01", code, factor, base, BigDecimal.ONE, null, null, null,
                base, active, 0, defaultShipping);
    }
}
