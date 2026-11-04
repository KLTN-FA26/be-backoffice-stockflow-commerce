package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(name = "SupplierSpend", description = "One supplier's row in the spend report")
public record SupplierSpendResponse(
        UUID supplierId,
        String supplierCode,
        String supplierName,

        @Schema(description = "ISO 4217 code. One row per supplier and currency: amounts in different "
                + "currencies are never added together.", example = "VND")
        String currency,

        @Schema(description = "Sum of totalAmount, in currency, over orders that are not DRAFT and not CANCELLED.")
        BigDecimal totalSpend,

        long purchaseOrderCount
) {
}
