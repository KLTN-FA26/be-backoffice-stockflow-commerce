package com.stockflow.catalog.api;

import com.stockflow.common.domain.Money;

import java.util.List;

/**
 * A customer's own price for one SKU, as tiers (kltn-docs 13 §4.2): from {@code minQuantity} on - cartons of the
 * customer's own SKU, units of a price kept on a stock variant - each piece costs {@code unitPrice}. {@code moq} is the
 * first tier's quantity. The only kind of price there is (13 BR-04): no base or group price stands behind it.
 */
public record PriceSchedule(String sku, int moq, List<Tier> tiers) {

    public record Tier(int minQuantity, Money unitPrice) {
    }

    public PriceSchedule {
        tiers = List.copyOf(tiers);
    }
}
