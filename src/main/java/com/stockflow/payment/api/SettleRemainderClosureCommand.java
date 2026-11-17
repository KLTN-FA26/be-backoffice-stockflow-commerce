package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The rest of a contract order was just closed (4B-9, kltn-docs 15 §4.6 step 19, 17 §4.8 step 33): what the order
 * module hands {@link PaymentService#settleRemainderClosure} in the closing transaction.
 *
 * @param closureId    names this closure: payment settles each closure once
 * @param closedAmount what the goods closed were worth with their VAT - never the mould / print or shipping fee lines,
 *                     which stay owed in full (open questions D30, D32)
 * @param feeAmount    an amount agreed with the customer for goods made before the closure, at most
 *                     {@code closedAmount}, with its reason and detail; null for none
 */
public record SettleRemainderClosureCommand(UUID orderId, UUID closureId, BigDecimal closedAmount,
                                            BigDecimal feeAmount, String feeReason, String feeDetail, UUID closedBy,
                                            Instant closedAt) {

    public SettleRemainderClosureCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(closureId, "closureId");
        Objects.requireNonNull(closedAmount, "closedAmount");
        Objects.requireNonNull(closedAt, "closedAt");
    }
}
