package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Start counting goods that arrived with no purchase order (SCRUM-478, BR-RCP-002): the supplier, the warehouse that
 * received them and why there is no PO (free text) are all required.
 */
public record CreateGoodsReceiptWithoutPoRequest(
        @NotNull UUID supplierId,
        @NotNull UUID warehouseId,
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 100) String deliveryNote,
        @Size(max = 2000) String note
) {
}
