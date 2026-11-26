package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One carton of a catalogue variant (docs 01 §3, BR-03): how many pieces it holds ({@code factor}, BR-09) and its own
 * size and weight. Every SKU of the variant - its customers' SKUs included - is packed in it (owner decision Q1,
 * V20261231000100). The pieces are counted in the item's own unit ({@code inventory_items.unit_of_measure}); that unit
 * has no row of its own since V20261226000200.
 *
 * <p>{@code sku}: the SKU the carton was asked for (a customer's SKU asked for gets its variant's cartons under its own
 * code); null when it was looked up by id alone, because a carton is no single SKU's.</p>
 *
 * <p>{@code active} false: retired (BR-10), kept for the documents that used it, never offered for new ones.</p>
 *
 * <p>The variant's one active unit is its carton (01 BR-03, {@code uk_uom_conversions_one_carton}): the one its SKUs are
 * received, sold and shipped in. There is no unit to choose to ship in, so no "default shipping" mark
 * (V20261229000100).</p>
 */
public record UomConversion(
        UUID id,
        String sku,
        String unitOfMeasure,
        int factor,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        boolean active,
        long version
) {
    /**
     * The carton of a SKU among its units (kltn-docs 01 BR-03, BR-11): its one active packing unit of more than one
     * piece. Empty for none - or for several, which {@code uk_uom_conversions_one_carton} does not allow: a SKU is sold
     * by the carton and nothing is guessed. The one rule a quote and the storefront's stock indicator both count
     * cartons by.
     */
    public static java.util.Optional<UomConversion> cartonOf(java.util.Collection<UomConversion> units) {
        java.util.List<UomConversion> candidates = units.stream().filter(UomConversion::cartonCandidate).toList();
        return candidates.size() == 1 ? java.util.Optional.of(candidates.getFirst()) : java.util.Optional.empty();
    }

    /**
     * An active packing unit of more than one piece: what a carton specification is (kltn-docs 01 BR-03). A SKU with at
     * least one has a carton specification - the design preflight's {@code CARTON_SPEC_MISSING} and the storefront's
     * {@code hasCartonSpec} ask exactly that.
     */
    public boolean cartonCandidate() {
        return active && factor > 1;
    }
}
