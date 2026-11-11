package com.stockflow.reporting.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What one order adds to the rollups: one order, its revenue and, per line, the SKU, units, revenue and cartons - all
 * on the day the order was placed. Cancelling the order subtracts exactly this. Since 4B-3 the revenue is the total
 * before tax and includes the fee lines (kltn-docs 17 §4.11, D33), so the lines add up to less than it when the order
 * has fees.
 *
 * @param day the Asia/Ho_Chi_Minh day the order was placed on, see {@link BusinessDay}
 */
public record OrderContribution(UUID orderId, LocalDate day, String currency, BigDecimal total, List<Line> lines) {

    public OrderContribution {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One order line: {@code revenue} is the line total before tax; {@code cartons} the cartons a line placed by the
     * carton holds (4B-3, D33), 0 for any other line and for what a return or a closure takes back - those count pieces.
     */
    public record Line(String sku, int quantity, BigDecimal revenue, int cartons) {

        /** Not counted in cartons. */
        public Line(String sku, int quantity, BigDecimal revenue) {
            this(sku, quantity, revenue, 0);
        }
    }
}
