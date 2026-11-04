package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Start counting a delivery against one purchase order. */
public record CreateGoodsReceiptRequest(
        @NotNull UUID purchaseOrderId,
        @Size(max = 100) String deliveryNote,
        @Size(max = 2000) String note
) {
}
