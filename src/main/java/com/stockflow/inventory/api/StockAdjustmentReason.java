package com.stockflow.inventory.api;

/** Why stock is corrected by hand. {@link #SCRAP} and {@link #SAMPLE} only write stock down. */
public enum StockAdjustmentReason {
    DAMAGED,
    LOST,
    FOUND,
    /**
     * Legacy: stock past its expiry date. Cups and packaging have none (kltn-docs 01 BR-17, 20 BR-13), so nothing asks
     * for it since team plan 5-1 and a request typed in with it is refused ({@code VALIDATION_FAILED}); kept because
     * adjustments recorded before carry it.
     */
    EXPIRED,
    DATA_CORRECTION,
    SCRAP,
    SAMPLE,
    OTHER,
    /** Blanks spoilt or missing at a print subcontractor (SCRUM-441). Only writes stock down. */
    SUBCONTRACT_LOSS,
    /**
     * What a cycle count found against the books (SCRUM-164). Read-only here: only a count asks for it, and a request
     * typed in with it is refused ({@code VALIDATION_FAILED}).
     */
    COUNT_VARIANCE
}
