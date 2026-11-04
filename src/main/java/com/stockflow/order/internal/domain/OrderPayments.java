package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payments the order has counted, one row per {@code PaymentCaptured} in
 * {@code ordering.order_payment} (SCRUM-460). It is what makes counting idempotent: the event is
 * delivered at least once, and a redelivery must not add the money twice.
 */
public interface OrderPayments {

    /** False when this payment was already counted for the order. */
    boolean record(UUID paymentId, UUID orderId, BigDecimal amount, String currency, Instant capturedAt);
}
