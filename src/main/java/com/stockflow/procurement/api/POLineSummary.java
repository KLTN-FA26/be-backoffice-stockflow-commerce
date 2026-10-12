package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One purchase order line. {@code quantityReceived} is what the confirmed goods receipts counted in
 * against it; {@code status} is OPEN, PARTIALLY_RECEIVED, RECEIVED, CLOSED or CANCELLED. The three
 * flags are the item's receiving policy (BR-03, 3-step flow), for a receipt form to check before
 * saving (SCRUM-464).
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
        String status,
        boolean lotTracked,
        boolean expiryTracked,
        boolean qcRequired
) {
    public int openQuantity() {
        return Math.max(0, quantityOrdered - quantityReceived);
    }
}
