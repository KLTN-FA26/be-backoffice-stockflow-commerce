package com.stockflow.catalog.internal.controller.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A customer's own price for a SKU, its MOQ (the first tier) and the version to send back on change. */
public record CustomerPriceResponse(UUID customerId, String sku, int moq, List<Tier> tiers, String note, long version) {

    public record Tier(int minQuantity, BigDecimal unitPrice) {
    }
}
