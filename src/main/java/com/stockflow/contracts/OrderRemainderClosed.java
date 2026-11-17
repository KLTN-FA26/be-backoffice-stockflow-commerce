package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when staff close what is left of a partly fulfilled order (split shipment,
 * decision G4-Q3; kltn-docs 17 edge "short pick: phần còn lại huỷ + hoàn tiền phần thiếu"), or when production
 * completes a print line short within what the customer agreed (review 2026-10-25, S6): the units in {@code lines}
 * will not be delivered.
 *
 * <p>Fulfillment stops the rest: open pick lines, and packages of those units not handed over, put back (G4-4 /
 * G4-5). Payment makes a contract's installments paid after delivery due if the order is now delivered in full. The
 * closure's money is not in this event: payment settles it on the installments in the closing transaction (4B-9) -
 * {@code closureId} is the key of that settlement.
 * {@code reasonCode} is a {@code CancellationReasonCode} name; there is no free text (A7). Events written while
 * in-house production existed may carry {@code PRODUCTION_SHORTFALL}: units production did not make, the customer
 * agreeing (S6).</p>
 *
 * <p>Review of #150 (decisions 2026-10-17 / 2026-10-19), fields added as the contract rules allow: print lines closed
 * after printing started may keep a {@code feeAmount} agreed with the customer.</p>
 *
 * <p>4B-10: the order counts no money of its own any more, so {@code paidAmount}, {@code refundableAmount},
 * {@code retainedFeeAmount} and {@code owedAmount} are always 0 - what was paid, is to refund and is owed is the
 * settlement's ({@code GET /api/v1/refunds/orders/{orderId}}). Kept so events written before still read.</p>
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
        UUID closureId,
        BigDecimal feeAmount,
        BigDecimal retainedFeeAmount,
        BigDecimal owedAmount
) {

    public OrderRemainderClosed {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Without a fee (events written before the review of #150). */
    public OrderRemainderClosed(UUID orderId, String orderNumber, UUID customerId, String reasonCode, List<Line> lines,
                                BigDecimal closedAmount, BigDecimal effectiveTotal, BigDecimal paidAmount,
                                BigDecimal refundableAmount, String currency, UUID closedBy, Instant closedAt,
                                UUID closureId) {
        this(orderId, orderNumber, customerId, reasonCode, lines, closedAmount, effectiveTotal, paidAmount,
                refundableAmount, currency, closedBy, closedAt, closureId, null, null, null);
    }

    /** {@code quantity} units of the line closed. */
    public record Line(UUID orderLineId, String sku, int quantity) {
    }
}
