package com.stockflow.production.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProductionOrderRowResponse(UUID id, String orderNumber, String type, String mode, String status,
                                         String holdReason, UUID warehouseId, String blankSku, UUID designSnapshotId,
                                         int plannedQuantity, int goodQuantity, LocalDate dueDate,
                                         UUID sampleRequestId, UUID salesOrderId, UUID salesOrderLineId,
                                         UUID parentId, Instant createdAt) {
}
