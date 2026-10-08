package com.stockflow.inventory.api;

/** {@code PENDING_APPROVAL → POSTED | REJECTED}. Approving posts, in the same transaction. */
public enum StockAdjustmentStatus {
    PENDING_APPROVAL,
    REJECTED,
    POSTED
}
