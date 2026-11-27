package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An order's money in three figures, for a row of a list (the back-office order list, audit bo-17): read for a whole
 * page at once by {@link PaymentService#paymentAmounts}, so the list does not call
 * {@code GET /api/v1/installments/orders/{orderId}} once per row. The figures are worked out as that endpoint and the
 * credit check work them out, so the screens agree.
 *
 * @param paid        what has been paid on the order's installments: {@link InstallmentStanding#paid}
 * @param outstanding what its installments not cancelled still owe: {@link InstallmentStanding#outstanding}, the
 *                    {@code outstanding} of {@code GET /api/v1/installments/orders/{orderId}}
 * @param overdue     what of that is past its due day on the business day of today: the installments that are
 *                    {@code overdue(today)} (overdue is a flag worked out from the due day, never a status), each for
 *                    what it still owes - the test and the amount of the credit check and
 *                    {@code payment.installment_is_overdue}. Every milestone counts, the one on signing included (the
 *                    receivables report shows that one apart, as {@code signingOverdue})
 */
public record OrderPaymentAmounts(UUID orderId, BigDecimal paid, BigDecimal outstanding, BigDecimal overdue) {
}
