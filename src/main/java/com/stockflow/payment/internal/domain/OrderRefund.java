package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One order's refund in the accountant's work list (4B-9, kltn-docs 15 §4.5): what its settlements made refundable and
 * what the live records say was refunded. Where it stands is {@link RefundStanding#of(UUID, BigDecimal, BigDecimal)}.
 */
public record OrderRefund(UUID orderId, String orderNumber, UUID customerId, String currency, BigDecimal refundable,
                          BigDecimal refunded) {
}
