package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when staff close what is left of a partly fulfilled order (split shipment,
 * decision G4-Q3; kltn-docs 17 edge "short pick: phần còn lại huỷ + hoàn tiền phần thiếu"): the units in
 * {@code lines} will not be delivered.
 *
 * <p>Payment asks back {@code refundableAmount} - the money received above what the order is now worth
 * ({@code effectiveTotal}), less what an earlier closure asked for - as PENDING refunds for the accountant.
 * {@code closureId} names this closure: payment asks each refund once per closure, so a redelivered event changes
 * nothing and a second closure of the same order (after a failed shipment came back) is not lost. Fulfillment stops
 * the rest: open pick lines, and packages of those units not handed over, put back (G4-4 / G4-5).
 * {@code reasonCode} is a {@code CancellationReasonCode} name; there is no free text (A7).</p>
 *
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record OrderRemainderClosed(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String reasonCode,
        List<Line> lines,
        BigDecimal closedAmount,
        BigDecimal effectiveTotal,
        BigDecimal paidAmount,
        BigDecimal refundableAmount,
        String currency,
        UUID closedBy,
        Instant closedAt,
        UUID closureId
) {

    public OrderRemainderClosed {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** {@code quantity} units of the line closed. */
    public record Line(UUID orderLineId, String sku, int quantity) {
    }
}
