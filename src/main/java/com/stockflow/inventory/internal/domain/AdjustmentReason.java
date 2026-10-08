package com.stockflow.inventory.internal.domain;

/**
 * Why stock was written up or down by hand (SCRUM-145). Mapped {@code EnumType.STRING} onto
 * {@code inventory.stock_adjustment.reason_code}, whose CHECK lists the same values.
 *
 * <p>{@code COUNT_VARIANCE} is in the table but not here: it is posted by a cycle count, which
 * links the count line, never typed in by a person.</p>
 *
 * <p>Production scrap and blanks used for samples are written off as {@link #DAMAGED} and
 * {@link #OTHER} until the table gains {@code SCRAP} and {@code SAMPLE} (production plan T4).</p>
 */
public enum AdjustmentReason {
    DAMAGED,
    LOST,
    FOUND,
    EXPIRED,
    DATA_CORRECTION,
    /** Needs a note: "other" with no explanation is not a reason. */
    OTHER
}
