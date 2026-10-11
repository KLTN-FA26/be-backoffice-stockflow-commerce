package com.stockflow.inventory.api;

/** Why stock is corrected by hand. {@link #SCRAP} and {@link #SAMPLE} only write stock down. */
public enum StockAdjustmentReason {
    DAMAGED,
    LOST,
    FOUND,
    EXPIRED,
    DATA_CORRECTION,
    SCRAP,
    SAMPLE,
    OTHER
}
