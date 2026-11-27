package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;

/**
 * How a customer has paid so far, for the accountant weighing a credit request (plan §4.4): read from the installments
 * of their signed contracts and the receipts that paid them, never from anything the customer or Sales said.
 *
 * @param settledContracts     contracts with nothing left to pay - every installment paid, or taken off by a closure
 *                             - and at least one paid
 * @param paidInstallments     installments with a due day, paid in full by live receipts
 * @param onTimeInstallments   of those, the ones whose last receipt was received on or before the due day
 * @param averageDaysLate      over the ones paid late, how many days after the due day the last receipt came, on
 *                             average; null when none was late
 * @param paidLast12Months     the live receipts received in the twelve months up to today
 */
public record PaymentHistory(int settledContracts, int paidInstallments, int onTimeInstallments,
                             BigDecimal averageDaysLate, BigDecimal paidLast12Months) {

    /** {@code onTimeInstallments / paidInstallments}, to four places; null when nothing was paid yet. */
    public BigDecimal onTimeRatio() {
        return paidInstallments == 0 ? null
                : BigDecimal.valueOf(onTimeInstallments).divide(BigDecimal.valueOf(paidInstallments), 4,
                java.math.RoundingMode.HALF_UP);
    }
}
