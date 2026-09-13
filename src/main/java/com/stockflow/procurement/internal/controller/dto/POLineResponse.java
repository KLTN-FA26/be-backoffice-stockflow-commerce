package com.stockflow.procurement.internal.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record POLineResponse(
        UUID lineId,
        String sku,
        String description,
        int quantityOrdered,
        int quantityReceived,
        int openQuantity,
        BigDecimal unitPrice
) {
}
