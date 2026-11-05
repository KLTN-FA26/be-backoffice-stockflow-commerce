package com.stockflow.procurement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One goods receipt as the receipt list shows it, without its lines and without the people who received and confirmed
 * it: where it stands, against which order and supplier, in which warehouse.
 *
 * @param status      one of {@link GoodsReceiptQueries#STATUSES}
 * @param receivedAt  when the delivery was counted in, the receipt's own date
 * @param confirmedAt when the count was confirmed and the goods became INBOUND stock; null while DRAFT or CANCELLED
 * @param closedAt    when the last of it was put away; null until CLOSED
 * @param withoutPurchaseOrder the goods came without a purchase order (SCRUM-478): no PO number, by design rather than
 *                    for want of data
 */
public record GoodsReceiptSummary(UUID receiptId, String number, String purchaseOrderNumber, String supplierName,
                                  String warehouseCode, String status, Instant receivedAt, Instant confirmedAt,
                                  Instant closedAt, boolean withoutPurchaseOrder) {
}
