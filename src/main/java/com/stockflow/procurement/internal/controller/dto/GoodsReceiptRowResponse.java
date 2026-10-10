package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** One row of the receipt list; no lines. */
public record GoodsReceiptRowResponse(UUID id, String number, UUID purchaseOrderId, UUID warehouseId, String status,
                                      String deliveryNote, Instant receivedAt, UUID receivedBy, Instant confirmedAt,
                                      Instant closedAt) {
}
