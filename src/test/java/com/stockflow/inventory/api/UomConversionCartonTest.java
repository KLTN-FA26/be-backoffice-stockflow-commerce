package com.stockflow.inventory.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 01 BR-03, BR-11: a SKU's carton is its one active packing unit of more than one piece; nothing is guessed.
 * The rule a quote and the storefront's stock indicator both count by (5-3).
 */
class UomConversionCartonTest {

    private static final UomConversion CARTON = unit("CARTON", 50, true);

    @Test
    void theOnlyActivePackingUnitIsTheCarton() {
        assertThat(UomConversion.cartonOf(List.of(CARTON))).contains(CARTON);
        assertThat(UomConversion.cartonOf(List.of(unit("OLD", 100, false), CARTON))).as("retired units beside it")
                .contains(CARTON);
    }

    @Test
    void aRetiredUnitOrOneOfASinglePieceIsNoCarton() {
        assertThat(UomConversion.cartonOf(List.of(unit("OLD", 100, false)))).isEmpty();
        assertThat(UomConversion.cartonOf(List.of(unit("SINGLE", 1, true)))).isEmpty();
        assertThat(UomConversion.cartonOf(List.of())).isEmpty();
    }

    @Test
    void severalActiveCartonsAreNoGuess() {
        // uk_uom_conversions_one_carton refuses this; should it ever be read, no carton is picked.
        assertThat(UomConversion.cartonOf(List.of(unit("BOX", 20, true), CARTON))).isEmpty();
    }

    private static UomConversion unit(String code, int factor, boolean active) {
        return new UomConversion(UUID.randomUUID(), "LY12-ACME-01", code, factor, BigDecimal.ONE, null, null, null,
                active, 0);
    }
}
