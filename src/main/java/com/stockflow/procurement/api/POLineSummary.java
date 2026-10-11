package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One purchase order line. {@code quantityReceived} is what the confirmed goods receipts counted in
 * against it; {@code status} is OPEN, PARTIALLY_RECEIVED, RECEIVED, CLOSED or CANCELLED.
 */
public record POLineSummary(
        UUID lineId,
        int lineNo,
        UUID inventoryItemId,
        String sku,
        String description,
        String uom,
        int quantityOrdered,
        int quantityReceived,
        BigDecimal unitPrice,
        BigDecimal taxRate,
        BigDecimal lineTotal,
        String status
) {
    public int openQuantity() {
        return Math.max(0, quantityOrdered - quantityReceived);
    }
}
