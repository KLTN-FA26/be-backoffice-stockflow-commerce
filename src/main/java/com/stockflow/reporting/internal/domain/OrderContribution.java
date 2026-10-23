package com.stockflow.reporting.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What one order adds to the rollups: one order, its total and, per line, the SKU, units and revenue - all on the day
 * the order was placed. Cancelling the order subtracts exactly this.
 *
 * @param day the Asia/Ho_Chi_Minh day the order was placed on, see {@link BusinessDay}
 */
public record OrderContribution(UUID orderId, LocalDate day, String currency, BigDecimal total, List<Line> lines) {

    public OrderContribution {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One order line: {@code revenue} is the line total, so the lines add up to the order total. */
    public record Line(String sku, int quantity, BigDecimal revenue) {
    }
}
