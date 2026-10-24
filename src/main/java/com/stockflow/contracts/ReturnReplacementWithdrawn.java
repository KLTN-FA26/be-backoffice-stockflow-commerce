package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the free replacement order of a resolved return is withdrawn before it
 * shipped (SCRUM-260): the customer would rather have the money, or the goods cannot be made again. The replacement
 * order is cancelled in the same transaction and the replaced units are credited at the prices of the return instead,
 * after a second person approved it.
 *
 * <ul>
 *   <li>{@code offsetAmount} reduced the original order's open receivables; {@code creditedAmount} stays as the
 *       customer's credit. Their sum is the value of the units the cancelled order would have shipped, per SKU in
 *       {@code lines}.</li>
 *   <li>{@code paidOrderId}: the order the goods were paid on, whose money took the credit.</li>
 * </ul>
 *
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record ReturnReplacementWithdrawn(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        UUID replacementOrderId,
        BigDecimal offsetAmount,
        BigDecimal creditedAmount,
        String currency,
        List<Line> lines,
        UUID paidOrderId,
        UUID approvedBy,
        Instant withdrawnAt
) {

    public ReturnReplacementWithdrawn {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** {@code quantity} units of {@code sku} that will not ship, credited at {@code amount}. */
    public record Line(UUID orderLineId, String sku, int quantity, BigDecimal amount) {
    }
}
