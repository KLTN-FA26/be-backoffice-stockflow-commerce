package com.stockflow.order.internal.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The human-facing reference a customer quotes on the phone: {@code SO-20260907-000431}.
 *
 * <p>Separate from {@link OrderId} because they answer different questions. The UUID is the
 * primary key and must never change; the order number is readable, sortable by date, and could be
 * reformatted tomorrow without touching a single foreign key.</p>
 */
public record OrderNumber(String value) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final java.util.regex.Pattern PATTERN =
            java.util.regex.Pattern.compile("SO-\\d{8}-\\d{6,}");

    public OrderNumber {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Malformed order number: " + value);
        }
    }

    /**
     * <p>Six digits is a minimum width, not a maximum: {@code %06d} pads but never truncates, and
     * the pattern accepts six <i>or more</i> digits. Order 1,000,000 within one day would
     * otherwise throw on every subsequent order that day - unlikely, and a total outage if it
     * ever happened.</p>
     *
     * @param dailySequence the nth order of that day, supplied by a database sequence rather than
     *                      counted in application code — two instances counting independently
     *                      would collide
     */
    public static OrderNumber of(LocalDate date, long dailySequence) {
        return new OrderNumber("SO-%s-%06d".formatted(DATE.format(date), dailySequence));
    }

    @Override
    public String toString() {
        return value;
    }
}
