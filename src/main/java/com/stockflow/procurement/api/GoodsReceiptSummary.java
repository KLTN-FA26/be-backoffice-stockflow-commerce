package com.stockflow.procurement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One goods receipt as the receipt list shows it, without its lines and without the people who received and confirmed
 * it: where it stands, against which production order and producer, in which warehouse. Purchasing is gone since the
 * rescope (4A-8); a receipt is against one production order (4A-5), or a legacy purchase receipt with none.
 *
 * @param status      one of {@link GoodsReceiptQueries#STATUSES}
 * @param receivedAt  when the delivery was counted in, the receipt's own date
 * @param confirmedAt when the count was confirmed and the goods became INBOUND stock; null while DRAFT or CANCELLED
 * @param closedAt    when the last of it was put away; null until CLOSED
 * @param receiptType PRODUCTION (against a production order), or PURCHASE for a legacy receipt from before the rescope
 * @param productionOrderCode the production order it receives; null for a legacy receipt
 * @param producerName        who printed the goods; null for a legacy receipt
 */
public record GoodsReceiptSummary(UUID receiptId, String number, String receiptType, String productionOrderCode,
                                  String producerName, String warehouseCode, String status, Instant receivedAt,
                                  Instant confirmedAt, Instant closedAt) {
}
