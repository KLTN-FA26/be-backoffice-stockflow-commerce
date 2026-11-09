package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Where an order's refund stands (4B-9, kltn-docs 15 §5.3 "Hoàn tiền của đơn - tính ra, không lưu trạng thái riêng"):
 * worked out from its settlements and the refunds recorded, never stored.
 *
 * <p>The order itself stays CANCELLED: 17 §5.1 makes CANCELLED final, and REFUNDED / PARTIALLY_REFUNDED are reached
 * from RETURNED only. The refund's own standing is what the accountant, Sales and the customer's portal read
 * instead.</p>
 *
 * @param refundable what the order's settlements made refundable (15 BR-14: never above what was collected)
 * @param refunded   what the live records say was refunded
 * @param remaining  what is still to refund
 */
public record RefundStanding(UUID orderId, BigDecimal refundable, BigDecimal refunded, BigDecimal remaining,
                             Status status) {

    /**
     * 15 §5.3: nothing to refund; waiting ("Chờ hoàn"); refunded in part ("Hoàn một phần"); refunded in full ("Đã
     * hoàn").
     */
    public enum Status { NONE, PENDING, PARTIALLY_REFUNDED, REFUNDED }

    public RefundStanding {
        Objects.requireNonNull(orderId, "orderId");
    }

    public static RefundStanding of(UUID orderId, List<OrderSettlement> settlements, List<RefundRecord> records) {
        BigDecimal refundable = settlements.stream().map(OrderSettlement::refundable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunded = records.stream().filter(r -> r.status() == RefundRecord.Status.RECORDED)
                .map(RefundRecord::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return of(orderId, refundable, refunded);
    }

    /** From the totals: what was made refundable and what the live records refunded. */
    public static RefundStanding of(UUID orderId, BigDecimal refundable, BigDecimal refunded) {
        BigDecimal remaining = refundable.subtract(refunded).max(BigDecimal.ZERO);
        Status status = refundable.signum() == 0 ? Status.NONE
                : refunded.signum() == 0 ? Status.PENDING
                : remaining.signum() > 0 ? Status.PARTIALLY_REFUNDED
                : Status.REFUNDED;
        return new RefundStanding(orderId, refundable, refunded, remaining, status);
    }
}
