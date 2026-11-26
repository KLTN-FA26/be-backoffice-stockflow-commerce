package com.stockflow.catalog.api;

import com.stockflow.common.domain.Money;

import java.util.List;

/**
 * A price for one SKU, as tiers (kltn-docs 13 §4.2): a customer's own price for its SKU, or the standard price of a
 * variant of the catalogue (13 BR-04, 2026-11-26). From {@code minQuantity} on - cartons; units of an old customer price
 * kept on a stock variant - each piece costs {@code unitPrice}. {@code moq} is the first tier's quantity.
 */
public record PriceSchedule(String sku, int moq, List<Tier> tiers) {

    public record Tier(int minQuantity, Money unitPrice) {
    }

    public PriceSchedule {
        tiers = List.copyOf(tiers);
    }
}
