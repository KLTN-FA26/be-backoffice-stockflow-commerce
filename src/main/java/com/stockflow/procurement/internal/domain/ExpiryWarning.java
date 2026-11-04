package com.stockflow.procurement.internal.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The "expiry too close to the receiving date" warning of docs 03 BR-06 ("cảnh báo nếu quá gần hạn theo
 * ngưỡng cấu hình") and its edge case (a lot with a shorter life than promised: warn, the manager decides).
 *
 * <p>Only a warning: BR-06's hard rule, an expiry date after the receiving date, is checked when the count
 * is entered ({@code EXPIRY_NOT_AFTER_RECEIPT}) and nothing here blocks a receipt. The window is in whole
 * calendar days of the business's calendar, counted from the day the goods were received, not from today:
 * a receipt looked at next month still says what it said on the day.</p>
 */
public final class ExpiryWarning {

    /** The code the receipt carries in {@code warnings}. */
    public static final String EXPIRY_NEAR = "EXPIRY_NEAR";

    private ExpiryWarning() {
    }

    /**
     * Whether {@code expiryDate} is at most {@code windowDays} days after {@code receivedOn}. A line with
     * no expiry date (the SKU is not expiry-tracked) never warns.
     */
    public static boolean isNear(LocalDate expiryDate, LocalDate receivedOn, int windowDays) {
        return expiryDate != null && ChronoUnit.DAYS.between(receivedOn, expiryDate) <= windowDays;
    }
}
