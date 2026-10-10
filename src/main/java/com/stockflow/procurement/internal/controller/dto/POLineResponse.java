package com.stockflow.procurement.internal.controller.dto;

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
        String status
) {
}
