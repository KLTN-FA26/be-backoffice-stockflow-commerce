package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A bank transfer waiting for the Accountant, and the installments it may pay, for a module that helps them choose
 * (the assistant's match suggestions, 2026-11-27 work features 2.1; docs/bank-feed-sepay.md). Read-only: assigning a
 * transfer stays the Accountant's, on the transfer's own screen, through every rule of a receipt.
 *
 * <p>Its own interface rather than more of {@link PaymentService}, which the order module calls inside its
 * transactions: a helper panel needs this read and nothing else.</p>
 */
public interface BankTransferReview {

    /**
     * One kept transfer, as the review screen shows it.
     *
     * @param status            {@code RECEIVED}, {@code APPLIED}, {@code NEEDS_REVIEW} or {@code IGNORED}
     * @param transactionAt     when the bank booked it: when the money came in
     * @param reviewReason      why the feed left it to the Accountant ({@code NO_INSTALLMENT_NAMED}, ...); null
     *                          unless it waits for review
     * @param hintInstallmentId the installment the feed read in its content, when it read one it could not pay (not
     *                          payable, owing less than the transfer brought); null otherwise
     */
    record Transfer(UUID id, String status, BigDecimal amount, Instant transactionAt, String content,
                    String reviewReason, UUID hintInstallmentId) {
    }

    /**
     * An installment the transfer may pay.
     *
     * @param namedInContent the content names exactly this installment the way the transfer card writes it, as the
     *                       bank feed reads it ({@code TransferReference}: the contract's number and its {@code DOTn}
     *                       or {@code FEEn}, with the dashes kept, spaced or dropped)
     */
    record Candidate(PayableInstallment installment, boolean namedInContent) {
    }

    /** The transfer; empty when there is none. */
    Optional<Transfer> transfer(UUID transactionId);

    /**
     * The installments a receipt may still pay, those the transfer most likely pays first: the one its content names,
     * the one the feed left as a hint, those owing exactly its amount, those of an order whose number its content
     * holds, then the rest by due day, earliest first. At most {@code limit} (1 to 500), read in one statement; empty
     * for an unknown transfer.
     */
    List<Candidate> candidates(UUID transactionId, int limit);
}
