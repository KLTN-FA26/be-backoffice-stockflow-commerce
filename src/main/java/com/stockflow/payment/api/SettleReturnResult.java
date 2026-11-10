package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What payment booked for a return. {@code offsetAmount + refundedAmount + creditedAmount} is the amount settled.
 *
 * @param offsetAmount     reduced what the order still owed; zero since the money ledger went (4B-10)
 * @param refundedAmount   to be refunded; zero since 4B-10, until 4D-3 works returns out from the installments
 * @param creditedAmount   credited to the customer, settled outside the system (kltn-docs 15 BR-06)
 * @param creditTransferId the money ledger's credit entry; always null since 4B-10, kept for the returns stored
 *                         before
 */
public record SettleReturnResult(
        UUID returnRequestId,
        BigDecimal offsetAmount,
        BigDecimal refundedAmount,
        BigDecimal creditedAmount,
        UUID creditTransferId
) {
}
