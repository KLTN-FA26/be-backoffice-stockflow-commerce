package com.stockflow.catalog.internal.controller.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A variant's standard price, where its first break starts and the version to send back on change.
 *
 * @param productName the name of the variant's product, so the price screen needs no product lookup; null if unknown
 * @param variantName the variant's own name; null if unknown
 */
public record StandardPriceResponse(String sku, String productName, String variantName, int moq, List<Tier> tiers,
                                    String note, long version) {

    /** From {@code minQuantity} cartons up, {@code unitPrice} VND a piece. */
    public record Tier(int minQuantity, BigDecimal unitPrice) {
    }
}
