package com.stockflow.catalog.internal.controller.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The prices a customer gets for a SKU: {@code source} CUSTOMER (its own tiers), SEGMENT or BASE (one
 * price from 1 unit on); {@code moq} is the first tier's quantity (kltn-docs 13 BR-05).
 */
public record PriceScheduleResponse(String sku, String source, int moq, String currency, List<Tier> tiers) {

    public record Tier(int minQuantity, BigDecimal unitPrice) {
    }
}
