package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when an order is cancelled, on every path: by the customer,
 * by Sales, on an approved cancellation request, after a failed payment, and when
 * an unpaid order's stock hold runs out (SCRUM-460).
 *
 * <p>kltn-docs 17 §4.5 and BR-03: a cancellation before shipping stops the warehouse work, so the modules that hold
 * that work react to this fact: fulfillment cancels the pick and pack; the order's production orders stop. The money
 * is not this event's: payment settles it on the contract's installments in the cancelling transaction (4B-9).</p>
 *
 * <p>{@code previousStatus} and {@code reasonCode} are the names of the order module's enums, as
 * strings, so this record imports nothing. 4B-10: {@code paidAmount}, {@code refundableAmount} and {@code owedAmount}
 * are always 0 - the order counts no money of its own; the settlement has them
 * ({@code GET /api/v1/refunds/orders/{orderId}}). Kept so events written before still read.</p>
 *
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made
 * durable by Spring Modulith's event publication registry, so a listener that crashes mid-handling
 * gets the event again. See {@code package-info.java} for the rules on changing this contract.
 */
public record OrderCancelled(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String previousStatus,
        String reasonCode,
        String note,
        BigDecimal paidAmount,
        BigDecimal refundableAmount,
        String currency,
        UUID cancelledBy,
        Instant cancelledAt,
        BigDecimal owedAmount
) {

    /** Without {@code owedAmount} (events written before decision Q6a): nothing more is owed. */
    public OrderCancelled(UUID orderId, String orderNumber, UUID customerId, String previousStatus, String reasonCode,
                          String note, BigDecimal paidAmount, BigDecimal refundableAmount, String currency,
                          UUID cancelledBy, Instant cancelledAt) {
        this(orderId, orderNumber, customerId, previousStatus, reasonCode, note, paidAmount, refundableAmount, currency,
                cancelledBy, cancelledAt, BigDecimal.ZERO);
    }
}
