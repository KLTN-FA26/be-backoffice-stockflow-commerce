package com.stockflow.inventory.internal.domain;

/**
 * Why stock was written up or down by hand (SCRUM-145). Mapped {@code EnumType.STRING} onto
 * {@code inventory.stock_adjustment.reason_code}, whose CHECK lists the same values.
 *
 * <p>{@code COUNT_VARIANCE} is in the table but not here: it is posted by a cycle count, which
 * links the count line, never typed in by a person.</p>
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
    OTHER;

    /** Scrap and samples consume blanks; they never bring stock back. */
    public boolean onlyWritesDown() {
        return this == SCRAP || this == SAMPLE;
    }
}
