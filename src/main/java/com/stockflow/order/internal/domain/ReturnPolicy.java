package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * The thresholds of the return rules, configured per deployment ({@code stockflow.order.returns.*}) because the docs
 * name a default for only one of them and the business will tune the rest. Passed to the aggregate, so its rules stay
 * plain Java and testable without a container.
 *
 * @param window                  BR-RMA-001 / OQ-07: how long after delivery a return may be opened. The same figure
 *                                as {@code stockflow.order.return-window-days} (7), after which an order completes
 *                                (BR-ORD-005): a return is possible exactly while the order could still complete
 * @param returnDeadline          how long the customer has to send approved goods back before the return expires (14
 *                                days: a B2B customer books its own carrier, often once a week)
 * @param noReturnMaxValue        the most a defect may be worth (unit price x approved units, VND) for Sales to accept
 *                                it on the photos and let the goods stay with the customer - shipping back low-value
 *                                printed cups costs more than they are worth, and printed goods cannot be resold
 * @param evidenceRequired        a defect is approved only with at least one photo (a short delivery needs none)
 * @param maxEvidence             photos one return may hold, all stages together
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
}
