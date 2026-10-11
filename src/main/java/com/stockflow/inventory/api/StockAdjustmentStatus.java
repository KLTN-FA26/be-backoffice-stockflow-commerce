package com.stockflow.inventory.api;

/**
 * {@code PENDING_APPROVAL → POSTED | REJECTED | WITHDRAWN}. Approving posts, in the same
 * transaction; the requester may withdraw while it is still pending.
 */
public enum StockAdjustmentStatus {
    PENDING_APPROVAL,
    REJECTED,
    POSTED,
    WITHDRAWN
}
