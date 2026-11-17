package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A contract order was just cancelled (4B-9, kltn-docs 15 §4.6 step 18, 17 §4.8 step 30): what the order module hands
 * {@link PaymentService#settleCancellation} in the cancelling transaction. The fee is a share of the money collected,
 * or an amount agreed with the customer with its reason and detail, or none; the order module has already refused one
 * before mass printing (17 BR-11) or above the order's worth.
 *
 * @param feePercent a share (0-100] of the money collected on the installments; null for none
 * @param feeAmount  an agreed amount, whole dong; null for none. Not with {@code feePercent}
 * @param cancelledBy null when the system cancelled the order (it keeps no fee then)
 */
public record SettleCancellationCommand(UUID orderId, BigDecimal feePercent, BigDecimal feeAmount, String feeReason,
                                        String feeDetail, UUID cancelledBy, Instant cancelledAt) {

    public SettleCancellationCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(cancelledAt, "cancelledAt");
    }
}
