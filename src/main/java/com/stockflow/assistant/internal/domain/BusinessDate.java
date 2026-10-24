package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The business runs on Vietnamese time: "today", a day's questions and a report's last day are all Asia/Ho_Chi_Minh
 * dates, whatever zone the server runs in.
 */
public final class BusinessDate {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private BusinessDate() {
    }

    public static LocalDate of(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    /** The first instant of the business day {@code instant} falls in. */
    public static Instant startOfDay(Instant instant) {
        return of(instant).atStartOfDay(ZONE).toInstant();
    }
}
