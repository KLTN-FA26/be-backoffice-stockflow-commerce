package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** One row of the receipt list; no lines. The names are for display; the ids stay for links. */
public record GoodsReceiptRowResponse(UUID id, String number, UUID purchaseOrderId, String purchaseOrderNumber,
                                      UUID supplierId, String supplierName, UUID warehouseId, String warehouseCode,
                                      String warehouseName, String status, String deliveryNote, Instant receivedAt,
                                      UUID receivedBy, String receivedByName, Instant confirmedAt, UUID confirmedBy,
                                      String confirmedByName, Instant closedAt,
                                      boolean withoutPurchaseOrder) {
}
