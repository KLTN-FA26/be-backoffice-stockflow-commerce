package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A goods receipt with its lines. {@code qcProgress} per line: NOT_REQUIRED (2-step flow),
 * AWAITING_MOVE_TO_QC, IN_QC_AREA, INSPECTED. {@code quantityForPutaway}: what waits for putaway.
 */
public record GoodsReceiptResponse(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber,
                                   UUID warehouseId, String status, String deliveryNote, String note,
                                   Instant receivedAt, UUID receivedBy, Instant confirmedAt, UUID confirmedBy,
                                   Instant closedAt, List<Line> lines) {

    public record Line(UUID id, UUID purchaseOrderLineId, Integer purchaseOrderLineNo, UUID inventoryItemId, String sku,
                       int quantity, String lotNumber, LocalDate expiryDate, String locationCode, String note,
                       boolean qcRequired, String qcProgress, String qcLocationCode, Instant movedToQcAt,
                       int quantityForPutaway, List<Inspection> inspections) {
    }

    public record Inspection(UUID id, String outcome, int quantity, String locationCode, String reason,
                             UUID inspectedBy, Instant inspectedAt) {
    }
}
