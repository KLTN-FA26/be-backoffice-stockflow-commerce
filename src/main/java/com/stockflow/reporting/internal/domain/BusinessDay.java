package com.stockflow.reporting.internal.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The calendar the dashboard counts in: a sale belongs to the Asia/Ho_Chi_Minh day it was placed on, not to the UTC
 * day. An order placed at 23:30 in Ho Chi Minh City is still that day's order, although it is already tomorrow in UTC.
 */
public final class BusinessDay {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private BusinessDay() {
    }

    public static LocalDate of(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** The key of a day in {@code reporting.product_sales_summary.period}: the ISO date. */
    public static String period(LocalDate day) {
        return day.toString();
    }
}
