package com.stockflow.catalog.internal.controller.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A customer's own price for a SKU, its MOQ (the first tier) and the version to send back on change.
 *
 * @param measure what the tiers count: {@code CARTON} for the customer's own SKU (13 BR-05); {@code UNIT} for a price
 *                set on a stock variant before, kept for checkout only - read and delete it, it is not set again
 */
public record CustomerPriceResponse(UUID customerId, String sku, String measure, int moq, List<Tier> tiers, String note,
                                    long version) {

    /** From {@code minQuantity} cartons (units on a {@code UNIT} price) up, {@code unitPrice} VND a piece. */
    public record Tier(int minQuantity, BigDecimal unitPrice) {
    }
}
