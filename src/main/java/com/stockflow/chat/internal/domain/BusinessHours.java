package com.stockflow.chat.internal.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * When Sales answer customers (kltn-docs 16 §4.1 step 3, open question F3; decided 2026-11-13: 08:00-17:30, Monday to
 * Saturday, Vietnam time). The chat's clocks count in these hours: a first answer due in 15 minutes is due 15 working
 * minutes later, so a question asked at 17:25 on Saturday is due at 08:10 on Monday - not overdue all Sunday - and the
 * customer is shown when to expect it. The resolution target is counted in working days of the same hours.
 *
 * <p>No public holidays: none was decided, and a holiday counted as a working day only makes an alert early, never a
 * conversation forgotten.</p>
 */
public record BusinessHours(ZoneId zone, Set<DayOfWeek> days, LocalTime opens, LocalTime closes) {

    public BusinessHours {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("Business hours need at least one working day");
        }
        if (!opens.isBefore(closes)) {
            throw new IllegalArgumentException("Business hours open before they close");
        }
        days = Set.copyOf(EnumSet.copyOf(days));
    }

    /** Whether Sales are at work at {@code at}. */
    public boolean isOpenAt(Instant at) {
        ZonedDateTime local = at.atZone(zone);
        LocalTime time = local.toLocalTime();
        return days.contains(local.getDayOfWeek()) && !time.isBefore(opens) && time.isBefore(closes);
    }

    /** {@code at} itself while open; otherwise the next opening. */
    public Instant nextOpening(Instant at) {
        ZonedDateTime local = at.atZone(zone);
        LocalDate day = local.toLocalDate();
        for (int i = 0; i <= 7; i++) {
            LocalDate candidate = day.plusDays(i);
            if (!days.contains(candidate.getDayOfWeek())) {
                continue;
            }
            Instant open = candidate.atTime(opens).atZone(zone).toInstant();
            Instant close = candidate.atTime(closes).atZone(zone).toInstant();
            if (at.isBefore(open)) {
                return open;
            }
            if (at.isBefore(close)) {
                return at;
            }
        }
        throw new IllegalStateException("No working day within a week");
    }

    /** The moment {@code amount} of working time after {@code from}: what a target in business hours is due at. */
    public Instant plus(Instant from, Duration amount) {
        Objects.requireNonNull(amount, "amount");
        Instant cursor = nextOpening(from);
        Duration left = amount;
        while (true) {
            Instant close = cursor.atZone(zone).toLocalDate().atTime(closes).atZone(zone).toInstant();
            Duration today = Duration.between(cursor, close);
            if (left.compareTo(today) <= 0) {
                return cursor.plus(left);
            }
            left = left.minus(today);
            cursor = nextOpening(close);
        }
    }

    /**
     * The moment {@code days} working days after {@code from}: the same time of day that many working days on, a
     * non-working day skipped (16 BR-09, the resolution target). Asked on Saturday at 17:00, two working days are due
     * on Tuesday at 17:00; asked outside business hours, they run from the next opening, so the second day ends at
     * closing. Counted as {@code days} times the working day's length, so a day and its hours stay one calendar.
     */
    public Instant plusWorkingDays(Instant from, int days) {
        if (days < 1) {
            throw new IllegalArgumentException("At least one working day");
        }
        return plus(from, Duration.between(opens, closes).multipliedBy(days));
    }

    /** Working time between two moments: how long a customer really waited (16 BR-09 measured in business hours). */
    public Duration between(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return Duration.ZERO;
        }
        Duration total = Duration.ZERO;
        Instant cursor = nextOpening(from);
        while (cursor.isBefore(to)) {
            Instant close = cursor.atZone(zone).toLocalDate().atTime(closes).atZone(zone).toInstant();
            Instant end = close.isBefore(to) ? close : to;
            total = total.plus(Duration.between(cursor, end));
            cursor = nextOpening(close);
        }
        return total;
    }
}
