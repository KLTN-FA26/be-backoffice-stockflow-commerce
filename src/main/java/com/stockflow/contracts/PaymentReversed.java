package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module when the accountant takes back money an allocation had brought to an order
 * (G2-3, kltn-docs 15 BR-06: the reversal is a new ledger row; nothing is edited). The order lowers what it has been
 * paid by {@code amount}; a confirmed order whose terms are no longer met goes ON_HOLD with reason PAYMENT - never
 * back to PENDING_PAYMENT, and never silently.
 *
 * @param reversalId  the reversal row: unique per reversal, so a redelivered event is counted once
 * @param allocationId the allocation reversed (the {@code paymentId} of the {@link PaymentCaptured} that brought it)
 * @param amount      positive: the money taken back
 * @param source      as {@link PaymentCaptured#source()}: {@code ORDER_ALLOCATION}, {@code RECEIVABLE_ALLOCATION} or
 *                    {@code FEE_RECEIVABLE_ALLOCATION}
 * <p>The accountant's reason stays with the reversal row in payment's ledger ({@code transfer_allocation.note}); the
 * event carries the ids that find it.</p>
 */
public record PaymentReversed(
        UUID orderId,
        UUID reversalId,
        UUID allocationId,
        BigDecimal amount,
        String currency,
        String source,
        UUID reversedBy,
        Instant reversedAt
) {
}
