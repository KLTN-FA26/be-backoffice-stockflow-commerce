package com.stockflow.inventory.api;

/** Why stock is corrected by hand. Production scrap is {@link #DAMAGED} for now. */
public enum StockAdjustmentReason {
    DAMAGED,
    LOST,
    FOUND,
    EXPIRED,
    DATA_CORRECTION,
    OTHER
}
