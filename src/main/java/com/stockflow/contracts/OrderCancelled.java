package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when an order is cancelled, on every path: by the customer,
 * by Sales or the coordinator, on an approved cancellation request, after a failed payment, and when
 * an unpaid order's stock hold runs out (SCRUM-460).
 *
 * <p>kltn-docs 17 §4.5 and BR-03: a cancellation before shipping stops the warehouse work and returns
 * or voids the money, so the modules that hold that work react to this fact: payment voids what was
 * not paid and asks for a refund of {@code refundableAmount}; fulfillment cancels the pick and pack;
 * production cancels or holds the print orders of the order.</p>
 *
 * <p>{@code previousStatus} and {@code reasonCode} are the names of the order module's enums, as
 * strings, so this record imports nothing. {@code paidAmount} is what the order had received;
 * {@code refundableAmount} is that minus the fee kept for work already done (kltn-docs 15 §4.4).</p>
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

    /**
     * {@code owedAmount}: what the customer agreed to pay on cancellation beyond the money already received
     * (decision Q6a, 2026-10-12) — payment records it as a receivable; zero or null when nothing more is owed.
     */
    public OrderCancelled(UUID orderId, String orderNumber, UUID customerId, String previousStatus, String reasonCode,
                          String note, BigDecimal paidAmount, BigDecimal refundableAmount, String currency,
                          UUID cancelledBy, Instant cancelledAt) {
        this(orderId, orderNumber, customerId, previousStatus, reasonCode, note, paidAmount, refundableAmount, currency,
                cancelledBy, cancelledAt, BigDecimal.ZERO);
    }
}
