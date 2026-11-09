package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module (4B-6, kltn-docs 15 §4.2 step 7) when the accountant records a receipt against
 * installments: the order module confirms an order waiting for its installment on signing once every such installment
 * is paid (BR-07). An announcement after commit: what the order does next never holds back, or rolls back, the money
 * recorded. Consumers receive it in-process through {@code @ApplicationModuleListener}; see {@code package-info.java}
 * for the rules on changing this contract.
 *
 * @param reference the bank statement line the receipt records
 */
public record PaymentReceiptRecorded(
        UUID receiptId,
        UUID customerId,
        String reference,
        BigDecimal amount,
        String currency,
        List<Line> lines,
        UUID recordedBy,
        Instant recordedAt
) {

    /**
     * One installment the receipt pays.
     *
     * @param milestone {@code ON_SIGNING}, {@code BEFORE_DELIVERY} or {@code AFTER_DELIVERY}
     * @param settled   whether the installment is paid in full now
     */
    public record Line(UUID installmentId, UUID orderId, String milestone, BigDecimal amount, boolean settled) {
    }
}
