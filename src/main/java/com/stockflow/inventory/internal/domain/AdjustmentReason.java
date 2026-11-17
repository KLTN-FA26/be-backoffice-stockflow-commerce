package com.stockflow.inventory.internal.domain;

/**
 * Why stock was written up or down by hand (SCRUM-145). Mapped {@code EnumType.STRING} onto
 * {@code inventory.stock_adjustment.reason_code}, whose CHECK lists the same values.
 *
 * <p>Kltn-docs 20 BR-04 lists six reasons, each either way: {@link #DAMAGED}, {@link #LOST}, {@link #FOUND},
 * {@link #DATA_CORRECTION}, {@link #OTHER} (with a note) and {@link #COUNT_VARIANCE}. The others are history - kept
 * because adjustments of the past name them - and a carton is never adjusted for one
 * ({@code tg_stock_adjustment_whole_carton}, V20261113000300).</p>
 *
 * <p>{@link #COUNT_VARIANCE} is the one reason a person cannot choose: a cycle count asks for it when it is
 * submitted, naming the count line, and the table refuses it without one
 * ({@code ck_stock_adjustment_count_variance}). The manual request path refuses it before the table has to.</p>
 */
public enum AdjustmentReason {
    DAMAGED,
    LOST,
    FOUND,
    /**
     * Legacy, read only: adjustments recorded before team plan 5-1 carry it. Stock no longer expires (kltn-docs 01
     * BR-17), the request path refuses it and {@code tg_stock_adjustment_no_new_expired} refuses a new row with it.
     */
    EXPIRED,
    DATA_CORRECTION,
    /** Blanks spoiled in printing or failed at QC (kltn-docs 19). Stock only goes down. */
    SCRAP,
    /** Blanks used to make a sample for the customer (kltn-docs 19 §4.1). Stock only goes down. */
    SAMPLE,
    /** Needs a note: "other" with no explanation is not a reason. */
    OTHER,
    /**
     * Legacy: blanks spoilt or missing at a print subcontractor (04c G8, decision Q9). Stock only goes down.
     * Subcontracting went with purchasing (4A-8): kept for the adjustments that have it, refused for a new one.
     */
    SUBCONTRACT_LOSS,
    /**
     * What a cycle count found against the books (SCRUM-164), either way. Only a count asks for it; approving one that
     * empties a carton marks the carton LOST, since a count that finds nothing found it missing.
     */
    COUNT_VARIANCE;

    /**
     * A reason only adjustments of the past carry: no new adjustment takes it. {@code ck_stock_adjustment_reason}
     * still lists it, for those rows; the refusal is here and in nothing else.
     */
    public boolean legacy() {
        return this == SUBCONTRACT_LOSS;
    }

    /** Scrap and samples consume blanks; they never bring stock back. */
    public boolean onlyWritesDown() {
        return this == SCRAP || this == SAMPLE || this == SUBCONTRACT_LOSS;
    }

    /** One of the five reasons a person gives for adjusting a carton by hand (BR-04, less COUNT_VARIANCE). */
    public boolean askableByHand() {
        return this == DAMAGED || this == LOST || this == FOUND || this == DATA_CORRECTION || this == OTHER;
    }

    /**
     * A carton written off for this reason is missing rather than gone: LOST (20 §4.6 step 7), so a count or a
     * restock can find it again. Written off for any other reason, it is CONSUMED.
     */
    public boolean leavesCartonLost() {
        return this == LOST || this == COUNT_VARIANCE;
    }
}
