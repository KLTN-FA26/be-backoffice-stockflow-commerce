package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module (4B-6, kltn-docs 15 §4.4) when the accountant voids a receipt: its lines no
 * longer pay their installments. A confirmed order left short of its installment on signing is put on hold for payment
 * by the order module (BR-13) - the accountant agreed to that before the void was allowed ({@code holdOrders}); it is
 * never silently taken back to unpaid. Consumers receive it in-process through {@code @ApplicationModuleListener}; see
 * {@code package-info.java} for the rules on changing this contract.
 *
 * @param reference  the bank statement line the receipt recorded
 * @param holdOrders the accountant agreed to put on hold a confirmed order the void leaves short
 */
public record PaymentReceiptVoided(
        UUID receiptId,
        UUID customerId,
        String reference,
        BigDecimal amount,
        String currency,
        List<Line> lines,
        boolean holdOrders,
        UUID voidedBy,
        Instant voidedAt
) {

    /**
     * One installment the receipt had paid.
     *
     * @param milestone {@code ON_SIGNING}, {@code BEFORE_DELIVERY} or {@code AFTER_DELIVERY}
     */
    public record Line(UUID installmentId, UUID orderId, String milestone, BigDecimal amount) {
    }
}
