package com.stockflow.payment.internal.domain;

/**
 * Where a transfer the bank feed reported stands ({@code ck_bank_transaction_status}).
 *
 * <ul>
 *   <li>{@code RECEIVED} - kept, not matched yet: being handled, or left by a failure the nightly catch-up retries.</li>
 *   <li>{@code APPLIED} - a receipt was recorded from it, automatically or by the Accountant.</li>
 *   <li>{@code NEEDS_REVIEW} - in the Accountant's queue, with why.</li>
 *   <li>{@code IGNORED} - the Accountant decided it is no customer's payment here, with why (15 BR-06: money that is
 *       not an installment's is settled in the accounting software).</li>
 * </ul>
 */
public enum BankTransactionStatus {
    RECEIVED,
    APPLIED,
    NEEDS_REVIEW,
    IGNORED
}
