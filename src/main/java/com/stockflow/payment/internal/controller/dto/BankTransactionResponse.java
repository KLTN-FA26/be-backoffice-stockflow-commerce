package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer the bank feed reported (credit plan 2026-11-26 part C), and what became of it.
 *
 * @param status           RECEIVED (being handled), APPLIED (a receipt was recorded from it), NEEDS_REVIEW (waits for
 *                         the Accountant), IGNORED
 * @param reviewReason     why it waits for the Accountant, for the screen to translate: NO_INSTALLMENT_NAMED,
 *                         SEVERAL_INSTALLMENTS, INSTALLMENT_NOT_FOUND, NOT_PAYABLE, OVERPAYMENT, OTHER_ACCOUNT, REFUSED,
 *                         RECEIPT_VOIDED; null unless NEEDS_REVIEW
 * @param reviewNote       the detail, in English, as the system wrote it
 * @param customerId       the customer whose installment the content names, when it names one: to start the
 *                         assignment from
 * @param installmentId    the one installment the content names, when it names exactly one
 * @param receiptReference the statement reference a receipt from it carries: the bank's reference, or
 *                         {@code SEPAY-<id>} when the bank gave none
 * @param receiptId        the receipt recorded from it, once APPLIED
 * @param automatic        APPLIED by the bank feed itself rather than assigned by the Accountant
 */
@Schema(description = "A bank transfer reported by the bank feed (SePay)")
public record BankTransactionResponse(
        UUID id,
        String provider,
        String source,
        String providerTransactionId,
        String accountNumber,
        BigDecimal amount,
        String currency,
        String content,
        String bankReference,
        String paymentCode,
        Instant transactionAt,
        Instant receivedAt,
        String status,
        String reviewReason,
        String reviewNote,
        UUID customerId,
        String customerCode,
        String customerName,
        UUID installmentId,
        String receiptReference,
        UUID receiptId,
        boolean automatic,
        Instant decidedAt,
        UUID reviewedBy,
        String reviewedByName,
        Instant reviewedAt,
        String ignoreReason
) {
}
