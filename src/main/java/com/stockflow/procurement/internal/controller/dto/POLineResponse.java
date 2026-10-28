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
        @Schema(description = "Goes through the QC area (3-step receiving)") boolean qcRequired,
        @Schema(description = "SCRUM-473: the packing unit ordered in; null for base units") String unitOfMeasure,
        @Schema(description = "Base units one of unitOfMeasure holds") Integer unitFactor,
        @Schema(description = "How many unitOfMeasure were ordered") Integer unitQuantity,
        @Schema(description = "Whole units still to receive: openQuantity / unitFactor, rounded down") Integer openUnitQuantity,
        @Schema(description = "unitPrice x unitFactor, for display; prices are kept per base unit") BigDecimal packingUnitPrice
) {
}
