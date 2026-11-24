package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One order's refund in the accountant's work list (4B-9, kltn-docs 15 §4.5): what its settlements made refundable,
 * what the live records say was refunded, and what is still to refund. Where it stands is
 * {@link RefundStanding#of(UUID, BigDecimal, BigDecimal)}.
 *
 * @param remaining refundable less refunded, never below zero: what the seller still owes the customer (TT-4 - the
 *                  order is not closed while it is above zero, and one closed before a void shows its debt only here)
 * @param feeOwed   what the order's fee items still owe (WF-05 BE-51; docs 17 §4.10 "tiền hoàn trừ vào phần khách còn
 *                  nợ trước"): shown beside {@code remaining}, so the accountant sees the customer owes a fee on the
 *                  same order before paying the refund out. Zero for none; never set off here
 */
public record OrderRefund(UUID orderId, String orderNumber, UUID customerId, String currency, BigDecimal refundable,
                          BigDecimal refunded, BigDecimal remaining, BigDecimal feeOwed) {

    public OrderRefund {
        feeOwed = feeOwed == null ? BigDecimal.ZERO : feeOwed;
    }

    /** Without the fee owed: zero. */
    public OrderRefund(UUID orderId, String orderNumber, UUID customerId, String currency, BigDecimal refundable,
                       BigDecimal refunded, BigDecimal remaining) {
        this(orderId, orderNumber, customerId, currency, refundable, refunded, remaining, BigDecimal.ZERO);
    }
}
