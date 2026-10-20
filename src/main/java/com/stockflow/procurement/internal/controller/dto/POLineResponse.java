package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** One purchase order line; {@code quantityReceived} is what confirmed goods receipts counted in. */
public record POLineResponse(
        UUID lineId,
        int lineNo,
        UUID inventoryItemId,
        String sku,
        String description,
        String uom,
        int quantityOrdered,
        int quantityReceived,
        int openQuantity,
        BigDecimal unitPrice,
        BigDecimal taxRate,
        BigDecimal lineTotal,
        String status,
        @Schema(description = "BR-03: a receipt line needs a lot number") boolean lotTracked,
        @Schema(description = "BR-03: a receipt line needs an expiry date after the receiving date") boolean expiryTracked,
        @Schema(description = "Goes through the QC area (3-step receiving)") boolean qcRequired
) {
}
