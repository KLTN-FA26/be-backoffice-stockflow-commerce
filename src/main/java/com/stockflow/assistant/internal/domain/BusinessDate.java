package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    /**
     * {@code instant} as a clock in Vietnam shows it, with the offset. A UTC time read as local is seven hours early,
     * and an event between midnight and 07:00 lands on the day before; a bare local time would lose the instant,
     * while this one still converts back exactly.
     */
    public static OffsetDateTime dateTime(Instant instant) {
        return instant.atZone(ZONE).toOffsetDateTime();
    }
}
