package com.stockflow.reporting.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The days a report covers, both ends inclusive. Nothing given means the last {@value #DEFAULT_DAYS} days up to
 * today; more than {@value #MAX_DAYS} days, or a start after the end, is refused (400) rather than answered slowly.
 */
public record ReportPeriod(LocalDate from, LocalDate to) {

    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_DAYS = 366;

    public ReportPeriod {
        if (from.isAfter(to)) {
            throw refused("from", "must not be after 'to'", "RangeInverted");
        }
        if (days(from, to) > MAX_DAYS) {
            throw refused("to", "the range is at most " + MAX_DAYS + " days", "RangeTooLong");
        }
    }

    /**
     * @param to   null means {@code today}
     * @param from null means {@value #DEFAULT_DAYS} days ending on {@code to}
     */
    public static ReportPeriod of(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to == null ? today : to;
        return new ReportPeriod(from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from, end);
    }

    public long days() {
        return days(from, to);
    }

    /** The same number of days immediately before this period, for "versus the previous period". */
    public ReportPeriod previous() {
        LocalDate previousTo = from.minusDays(1);
        return new ReportPeriod(previousTo.minusDays(days() - 1), previousTo);
    }

    private static long days(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    private static BusinessException refused(String field, String message, String code) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid date range: '" + field + "' " + message,
                List.of(new FieldError(field, message, code)));
    }
}
