package com.stockflow.production.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code packingLocation}: the PACKING area a completed ORDER's goods were handed over to, automatically, by the
 * step that completed it (SCRUM-400); null before completion, for a SAMPLE, and for an order completed before
 * this was recorded without a ledger line to tell.
 */
public record ProductionOrderRowResponse(UUID id, String orderNumber, String type, String mode, String status,
                                         String holdReason, UUID warehouseId, String blankSku, UUID designSnapshotId,
                                         int plannedQuantity, int goodQuantity, LocalDate dueDate,
                                         UUID sampleRequestId, UUID salesOrderId, UUID salesOrderLineId,
                                         UUID parentId, Instant createdAt,
                                         String packingLocation) {
}
