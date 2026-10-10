package com.stockflow.procurement.api;

import java.math.BigDecimal;

/**
 * One line to order: a SKU that has an inventory item (BR-01), how many, at what price.
 * {@code description} falls back to the product name; {@code taxRate} (percent) to 0.
 */
public record CreatePOLineCommand(String sku, String description, int quantityOrdered, BigDecimal unitPrice,
                                  BigDecimal taxRate) {

    public CreatePOLineCommand(String sku, String description, int quantityOrdered, BigDecimal unitPrice) {
        this(sku, description, quantityOrdered, unitPrice, null);
    }
}
