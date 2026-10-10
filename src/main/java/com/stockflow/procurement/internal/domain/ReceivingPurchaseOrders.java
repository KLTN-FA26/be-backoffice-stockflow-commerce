package com.stockflow.procurement.internal.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port over the new purchase-order tables, for receiving only; see {@link ReceivingPurchaseOrder}. */
public interface ReceivingPurchaseOrders {

    Optional<ReceivingPurchaseOrder> find(UUID purchaseOrderId);

    /**
     * The order, with its row locked until the transaction ends: two receipts of the same order
     * confirmed at the same moment would otherwise both compute the order's status from stale totals.
     */
    Optional<ReceivingPurchaseOrder> lock(UUID purchaseOrderId);

    /**
     * Write what a confirmed receipt changed: each line's status, the order's status, and one
     * {@code purchase_order_events} row when the order's status changed.
     */
    void recordProgress(ReceivingPurchaseOrder order, Map<UUID, ReceivingPurchaseOrder.LineStatus> lineStatuses,
                        ReceivingPurchaseOrder.Status newStatus, UUID actorId, UUID receiptId, String receiptNumber);
}
