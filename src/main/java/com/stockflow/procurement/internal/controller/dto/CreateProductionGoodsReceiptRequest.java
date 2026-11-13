package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Start counting a producer's delivery against one production order (4A-5, kltn-docs 03 §4.1 step 1).
 * {@code deliveryNote}: the producer's delivery slip, which names the production order and the cartons.
 */
public record CreateProductionGoodsReceiptRequest(
        @NotNull UUID productionOrderId,
        @Size(max = 100) String deliveryNote,
        @Size(max = 2000) String note
) {
}
