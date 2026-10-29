package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.BusinessCalendar;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The thresholds of the return rules, configured per deployment ({@code stockflow.order.returns.*}) because the docs
 * name a default for only one of them and the business will tune the rest. Passed to the aggregate, so its rules stay
 * plain Java and testable without a container.
 *
 * <p>Both periods are counted in calendar days of Asia/Ho_Chi_Minh, as a customer and a warehouse count them: "7 days
 * from delivery" includes the whole seventh day, whatever the hour the goods arrived - an exact 168 hours would refuse
 * a claim made at 18:00 on the seventh day for goods delivered at 17:00, and the order would complete on the same
 * hour-based clock while the customer still thinks the window open.</p>
 *
 * @param window                  BR-RMA-001 / OQ-07: how long after delivery a return may be opened. The same figure
 *                                as {@code stockflow.order.return-window-days} (7), after which an order completes
 *                                (BR-ORD-005): a return is possible exactly while the order could still complete
 * @param returnDeadline          how long the customer has to send approved goods back before the return expires (14
 *                                days: a B2B customer books its own carrier, often once a week)
 * @param noReturnMaxValue        the most a defect may be worth (unit price x approved units, VND) for Sales to accept
 *                                it on the photos and let the goods stay with the customer - shipping back low-value
 *                                printed cups costs more than they are worth, and printed goods cannot be resold
 * @param evidenceRequired        a claim is approved only with at least one photo of the problem: the defect, or for a
 *                                short delivery the delivery note the carrier signed with the shortage written on it
 * @param maxEvidence             photos one return may hold at each stage (the customer's claim, the warehouse's
 *                                receipt, QC's inspection): a customer who sent many never leaves QC without room
 * @param changeOfMindForStockItems whether a stock item (never a printed one, BR-RMA-002) may come back because the
 *                                customer changed their mind; false by default - SCRUM-243 narrowed returns to defects
 */
public record ReturnPolicy(Duration window, Duration returnDeadline, BigDecimal noReturnMaxValue,
                           boolean evidenceRequired, int maxEvidence, boolean changeOfMindForStockItems) {

    public ReturnPolicy {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(returnDeadline, "returnDeadline");
        Objects.requireNonNull(noReturnMaxValue, "noReturnMaxValue");
        if (window.isNegative() || returnDeadline.isNegative() || returnDeadline.isZero()
                || noReturnMaxValue.signum() < 0 || maxEvidence < 1) {
            throw new IllegalArgumentException("Return policy thresholds are positive");
        }
    }

    /** The defaults described above. */
    public static ReturnPolicy defaults() {
        return new ReturnPolicy(Duration.ofDays(7), Duration.ofDays(14), BigDecimal.valueOf(2_000_000), true, 10,
                false);
    }

    /** When the return window of goods delivered at {@code deliveredAt} closes: the start of the day after its last. */
    public Instant windowClosesAt(Instant deliveredAt) {
        return endOfDays(deliveredAt, window);
    }

    /** The deadline for goods approved at {@code approvedAt} to come back: the end of the last day, as a start-of-day. */
    public Instant returnDeadlineFrom(Instant approvedAt) {
        return endOfDays(approvedAt, returnDeadline);
    }

    /**
     * The instant {@code days} whole calendar days after the day of {@code from} have passed (Asia/Ho_Chi_Minh): the
     * start of the following day. Shared with {@link Order#complete}, so a claim and the order's completion read the
     * same clock.
     */
    public static Instant endOfDays(Instant from, Duration days) {
        return BusinessCalendar.date(Objects.requireNonNull(from, "from")).plusDays(days.toDays() + 1)
                .atStartOfDay(BusinessCalendar.ZONE).toInstant();
    }
}
