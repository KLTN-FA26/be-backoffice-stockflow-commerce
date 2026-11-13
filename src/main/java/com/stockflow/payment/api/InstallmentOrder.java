package com.stockflow.payment.api;

import java.util.UUID;

/**
 * An order as payment sees it (4B-10): only what receipts, voids and refund records decide on.
 *
 * @param status   the order's status, e.g. {@code PENDING_PAYMENT}, {@code CANCELLED} - the order module's name
 * @param holdable whether the order can be put on hold now (a receipt void leaving it short of its installment on
 *                 signing holds it, 15 BR-13)
 */
public record InstallmentOrder(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String status,
        String currency,
        boolean holdable
) {
}
