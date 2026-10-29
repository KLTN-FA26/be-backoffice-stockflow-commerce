package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What payment booked for a return. {@code offsetAmount + refundedAmount + creditedAmount} is the amount settled.
 *
 * @param offsetAmount     reduced the order's open receivables
 * @param refundedAmount   asked back as PENDING refunds (source RETURN) for the accountant to pay out
 * @param creditedAmount   kept as the customer's credit
 * @param creditTransferId the ledger entry of the credit (channel RETURN_CREDIT) when something was set off or kept;
 *                         null when everything was refunded
 */
public record SettleReturnResult(
        UUID returnRequestId,
        BigDecimal offsetAmount,
        BigDecimal refundedAmount,
        BigDecimal creditedAmount,
        UUID creditTransferId
) {
}
