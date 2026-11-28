package com.stockflow.payment.api;

import com.stockflow.common.api.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The accountant's two queues, counted, for another module to show what waits (the back-office assistant's work
 * queue, contract 3.1): the bank transfers waiting for review ({@code GET /api/v1/bank-transactions?status=NEEDS_REVIEW})
 * and the installments overdue ({@code GET /api/v1/installments?overdue=true}). The same reads those screens make,
 * the first rows and the count of all; nothing else is answered here.
 *
 * <p><b>Scope.</b> Both screens are company-wide ({@code DataScope.ALL}) and staff-only; call it inside the guard of
 * the screen whose list is wanted ({@code payment-bank-transactions:READ}, {@code payment-installments:READ}).</p>
 */
public interface PaymentQueues {

    /** The most rows one call returns. */
    int MAX_ROWS = 50;

    /**
     * Transfers SePay reported that no receipt was recorded for, newest first: what the accountant matches by hand.
     *
     * @param limit 1..{@value #MAX_ROWS}; the page's total counts all of them
     */
    PageResponse<TransferToReview> transfersToReview(int limit);

    /**
     * Installments past their due day and still owed (overdue is worked out from the due day, not a status), the
     * earliest due first.
     *
     * @param limit 1..{@value #MAX_ROWS}; the page's total counts all of them
     */
    PageResponse<OverdueInstallment> overdueInstallments(int limit);

    /**
     * @param reviewReason why it was not matched (NO_INSTALLMENT_NAMED, SEVERAL_INSTALLMENTS...); null when not given
     */
    record TransferToReview(UUID transactionId, BigDecimal amount, Instant transactionAt, Instant receivedAt,
                            String reviewReason) {
    }

    /** @param outstanding what is still owed on it */
    record OverdueInstallment(UUID installmentId, UUID orderId, String orderNumber, String contractNumber,
                              UUID customerId, int seq, LocalDate dueDate, BigDecimal outstanding, String currency) {
    }
}
