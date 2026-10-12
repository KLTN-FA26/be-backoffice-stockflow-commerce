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
                                   UUID supplierId, String supplierName, UUID warehouseId, String warehouseCode,
                                   String warehouseName, String status, String deliveryNote, String note,
                                   Instant receivedAt, UUID receivedBy, String receivedByName, Instant confirmedAt,
                                   UUID confirmedBy, String confirmedByName, Instant closedAt, List<Line> lines) {

    /** {@code lotTracked} / {@code expiryTracked}: whether the line needs a lot / an expiry date (BR-03). */
    public record Line(UUID id, UUID purchaseOrderLineId, Integer purchaseOrderLineNo, UUID inventoryItemId, String sku,
                       int quantity, String lotNumber, LocalDate expiryDate, String locationCode, String note,
                       boolean qcRequired, boolean lotTracked, boolean expiryTracked, String qcProgress,
                       String qcLocationCode, Instant movedToQcAt, int quantityForPutaway,
                       List<Inspection> inspections) {
    }

    public record Inspection(UUID id, String outcome, int quantity, String locationCode, String reason,
                             UUID inspectedBy, String inspectedByName, Instant inspectedAt) {
    }
}
