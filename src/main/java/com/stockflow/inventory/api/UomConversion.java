package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One packing unit of a SKU's inventory item (docs 01 §3 "Đơn vị quy đổi"): a carton specification - how many pieces
 * it holds ({@code factor}, BR-09) and its own size and weight. The pieces are counted in the item's own unit
 * ({@code inventory_items.unit_of_measure}, "cái"); that unit has no row of its own since V20261226000200: a row saying
 * "one piece is one piece" was only ever skipped or refused.
 *
 * <p>{@code active} false: retired (BR-10), kept for the documents that used it, never offered for new ones.</p>
 *
 * <p>{@code defaultShipping}: the unit load planning estimates this SKU's cartons in (one per item);
 * {@link PackagingSpecResolver} falls back to the largest packing unit with a
 * full size when no unit carries it. Never set on a retired unit.</p>
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
        long version,
        boolean defaultShipping
) {
    /**
     * The carton of a SKU among its units (kltn-docs 01 BR-03, BR-11): its one active packing unit of more than one
     * piece - the default shipping one should an older SKU have several. Empty for none, or for several none of which
     * is the default shipping unit: a SKU is sold by the carton and nothing is guessed. The one rule a quote and the
     * storefront's stock indicator both count cartons by.
     */
    public static java.util.Optional<UomConversion> cartonOf(java.util.Collection<UomConversion> units) {
        java.util.List<UomConversion> candidates = units.stream().filter(UomConversion::cartonCandidate).toList();
        if (candidates.size() > 1) {
            candidates = candidates.stream().filter(UomConversion::defaultShipping).toList();
        }
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

    /** A unit that is not the default shipping unit. */
    public UomConversion(UUID id, String sku, String unitOfMeasure, int factor, BigDecimal weightKg,
                         BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                         boolean active, long version) {
        this(id, sku, unitOfMeasure, factor, weightKg, lengthCm, widthCm, heightCm, active, version, false);
    }
}
