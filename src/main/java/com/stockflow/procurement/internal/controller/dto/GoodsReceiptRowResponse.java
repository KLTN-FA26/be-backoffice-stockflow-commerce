package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the receipt list; no lines. The names are for display; the ids stay for links. {@code receiptType}:
 * PRODUCTION (4A-5), or PURCHASE on a historical receipt of the purchasing there was (4A-8); {@code productionOrderId},
 * {@code productionOrderCode}, {@code producerName}: the production order of a PRODUCTION receipt, null otherwise.
 */
public record GoodsReceiptRowResponse(UUID id, String number, UUID warehouseId, String warehouseCode,
                                      String warehouseName, String status, String deliveryNote, Instant receivedAt,
                                      UUID receivedBy, String receivedByName, Instant confirmedAt, UUID confirmedBy,
                                      String confirmedByName, Instant closedAt, String receiptType,
                                      UUID productionOrderId, String productionOrderCode, String producerName) {
}
