package com.stockflow.catalog.api;

import com.stockflow.common.domain.Money;

import java.util.List;

/**
 * A customer's own price for one SKU, as tiers (kltn-docs 13 §4.2): from {@code minQuantity} on - cartons of the
 * customer's own SKU, units of a price kept on a stock variant - each piece costs {@code unitPrice}. {@code moq} is the
 * first tier's quantity. The only kind of price there is (13 BR-04): no base or group price stands behind it.
 *
 * @param measure what {@code minQuantity} and {@code moq} count, as the customer-prices screen says it: {@code CARTON}
 *                for the customer's own SKU (13 BR-05), {@code UNIT} for a price kept on a stock variant from before
 *                4B-2. Carried because the same number means fifty times as much in one as in the other, and a reader
 *                told "from 10,000" cannot tell which; null only on a schedule built without it
 *                ({@link #PriceSchedule(String, int, List)})
 */
public record PriceSchedule(String sku, int moq, List<Tier> tiers, Measure measure) {

    public record Tier(int minQuantity, Money unitPrice) {
    }

    /** What a tier's quantity counts. */
    public enum Measure {
        /** Whole cartons of the SKU's carton unit: the customer's own SKU (13 BR-05). */
        CARTON,
        /** Pieces: a price kept on a stock variant, from before prices moved to the customer's own SKUs. */
        UNIT
    }

    public PriceSchedule {
        tiers = List.copyOf(tiers);
    }

    /** A schedule whose measure is not said; the catalog's own answer always says it. */
    public PriceSchedule(String sku, int moq, List<Tier> tiers) {
        this(sku, moq, tiers, null);
    }

    /** This schedule, counting in {@code measure}. */
    public PriceSchedule measuredIn(Measure measure) {
        return new PriceSchedule(sku, moq, tiers, measure);
    }
}
