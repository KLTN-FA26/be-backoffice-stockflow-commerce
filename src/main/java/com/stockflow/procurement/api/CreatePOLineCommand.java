package com.stockflow.procurement.api;

import java.math.BigDecimal;

/**
 * One line to order: a SKU that has an inventory item (BR-01) and that the supplier lists, how many, at
 * what price (SCRUM-78). {@code unitPrice} null takes the catalog price, which needs the order to be in the
 * catalog's currency; {@code description} falls back to the product name; {@code taxRate} (percent) to 0.
 */
public record CreatePOLineCommand(String sku, String description, int quantityOrdered, BigDecimal unitPrice,
                                  BigDecimal taxRate) {

    public CreatePOLineCommand(String sku, String description, int quantityOrdered, BigDecimal unitPrice) {
        this(sku, description, quantityOrdered, unitPrice, null);
    }
}
