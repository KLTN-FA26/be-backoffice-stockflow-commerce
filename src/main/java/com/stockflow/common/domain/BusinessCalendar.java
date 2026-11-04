package com.stockflow.common.domain;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
/** Vietnam operating calendar; timestamp storage and expiry calculations remain UTC. */
public final class BusinessCalendar {
    public static final String ZONE_NAME = "Asia/Ho_Chi_Minh";
    public static final ZoneId ZONE = ZoneId.of(ZONE_NAME);
    private BusinessCalendar() {}
    public static LocalDate date(Instant instant) { return instant.atZone(ZONE).toLocalDate(); }
}
