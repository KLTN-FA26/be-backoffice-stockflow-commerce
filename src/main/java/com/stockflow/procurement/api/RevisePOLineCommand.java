package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a revised draft. {@code lineId} keeps a line the draft has (same SKU); null adds one.
 * {@code unitPrice} null takes the catalog price, as on create (SCRUM-78); {@code description} falls back
 * to the product name; {@code taxRate} (percent) to 0.
 */
public record RevisePOLineCommand(UUID lineId, String sku, String description, Integer quantityOrdered,
                                  BigDecimal unitPrice, BigDecimal taxRate, String unitOfMeasure,
                                  Integer unitQuantity) {
    /** Naming no unit: a new line is in base units, an existing one keeps its own. */
    public RevisePOLineCommand(UUID lineId, String sku, String description, int quantityOrdered, BigDecimal unitPrice,
                               BigDecimal taxRate) {
        this(lineId, sku, description, quantityOrdered, unitPrice, taxRate, null, null);
    }
}
