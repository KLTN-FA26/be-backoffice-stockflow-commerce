package com.stockflow.inventory.internal.domain;

/**
 * Why stock was written up or down by hand (SCRUM-145). Mapped {@code EnumType.STRING} onto
 * {@code inventory.stock_adjustment.reason_code}, whose CHECK lists the same values.
 *
 * <p>{@link #COUNT_VARIANCE} is the one reason a person cannot choose: a cycle count asks for it when it is
 * submitted, naming the count line, and the table refuses it without one
 * ({@code ck_stock_adjustment_count_variance}). The manual request path refuses it before the table has to.</p>
 */
public enum AdjustmentReason {
    DAMAGED,
    LOST,
    FOUND,
    EXPIRED,
    DATA_CORRECTION,
    /** Blanks spoiled in printing or failed at QC (kltn-docs 19). Stock only goes down. */
    SCRAP,
    /** Blanks used to make a sample for the customer (kltn-docs 19 §4.1). Stock only goes down. */
    SAMPLE,
    /** Needs a note: "other" with no explanation is not a reason. */
    OTHER,
    /** Blanks spoilt or missing at a print subcontractor (04c G8, decision Q9). Stock only goes down. */
    SUBCONTRACT_LOSS,
    /**
     * What a cycle count found against the books (SCRUM-164), either way. Only a count asks for it; approving one that
     * empties a carton marks the carton LOST, since a count that finds nothing found it missing.
     */
    COUNT_VARIANCE;

    /** Scrap and samples consume blanks; they never bring stock back. */
    public boolean onlyWritesDown() {
        return this == SCRAP || this == SAMPLE || this == SUBCONTRACT_LOSS;
    }
}
