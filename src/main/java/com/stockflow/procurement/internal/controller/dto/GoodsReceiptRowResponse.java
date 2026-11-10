package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the receipt list; no lines. The names are for display; the ids stay for links. {@code receiptType}:
 * PURCHASE or PRODUCTION (4A-5); {@code productionOrderId}, {@code productionOrderCode}, {@code producerName}: the
 * production order of a PRODUCTION receipt, null on a purchase receipt.
 */
public record GoodsReceiptRowResponse(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber,
                                      UUID supplierId, String supplierName, UUID warehouseId, String warehouseCode,
                                      String warehouseName, String status, String deliveryNote, Instant receivedAt,
                                      UUID receivedBy, String receivedByName, Instant confirmedAt, UUID confirmedBy,
                                      String confirmedByName, Instant closedAt,
                                      boolean withoutPurchaseOrder, String receiptType, UUID productionOrderId,
                                      String productionOrderCode, String producerName) {
}
