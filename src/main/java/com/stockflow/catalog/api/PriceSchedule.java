package com.stockflow.catalog.api;

import com.stockflow.common.domain.Money;

import java.util.List;

/**
 * The prices a customer gets for one SKU: its own tiers, or - with no own price - its group's or the
 * base price from one unit on (kltn-docs 13 §3). {@code moq} is the first tier's quantity.
 */
public record PriceSchedule(String sku, PriceSource source, int moq, List<Tier> tiers) {

    public enum PriceSource { CUSTOMER, SEGMENT, BASE }

    public record Tier(int minQuantity, Money unitPrice) {
    }

    public PriceSchedule {
        tiers = List.copyOf(tiers);
    }
}
