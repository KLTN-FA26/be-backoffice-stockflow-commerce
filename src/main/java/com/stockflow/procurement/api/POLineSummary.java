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
    public int openQuantity() {
        return Math.max(0, quantityOrdered - quantityReceived);
    }

    /** Whole packing units still to receive, rounded down; null for a line in base units. */
    public Integer openUnitQuantity() {
        return unitFactor == null ? null : openQuantity() / unitFactor;
    }

    /** The price of one packing unit, for display only - prices are kept per base unit; null for base units. */
    public BigDecimal packingUnitPrice() {
        return unitFactor == null ? null : unitPrice.multiply(BigDecimal.valueOf(unitFactor));
    }
}
