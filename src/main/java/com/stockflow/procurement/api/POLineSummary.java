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
        boolean qcRequired,
        String unitOfMeasure,
        Integer unitFactor,
        Integer unitQuantity
) {
    /**
     * What a receiver may still expect on this line: nothing on a CANCELLED, WITHDRAWN or CLOSED line - one an
     * approved amendment removed (SCRUM-117), or the remainder an order closed short wrote off - whatever was ordered.
     */
    public int openQuantity() {
        if ("CANCELLED".equals(status) || "WITHDRAWN".equals(status) || "CLOSED".equals(status)) {
            return 0;
        }
        return Math.max(0, quantityOrdered - quantityReceived);
    }

    /** Whole packing units still to receive ({@link #openQuantity}), rounded down; null for a line in base units. */
    public Integer openUnitQuantity() {
        return unitFactor == null ? null : openQuantity() / unitFactor;
    }

    /** The price of one packing unit, for display only - prices are kept per base unit; null for base units. */
    public BigDecimal packingUnitPrice() {
        return unitFactor == null ? null : unitPrice.multiply(BigDecimal.valueOf(unitFactor));
    }
}
