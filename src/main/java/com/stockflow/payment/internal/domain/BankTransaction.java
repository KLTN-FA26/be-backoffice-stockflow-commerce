package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An incoming transfer the bank feed reported (15 BR-02, §4.2; credit plan 2026-11-26 part C), kept before anything is
 * done with it and then decided once: {@link #applied} when a receipt was recorded from it, {@link #needsReview} for the
 * Accountant, {@link #ignore ignored} by the Accountant with a reason. A transfer in the queue may still be applied - the
 * Accountant assigns it - and one whose receipt was voided {@link #receiptVoided goes back} to the queue: the money came
 * in, and is assigned again or ignored. The rules are the table's checks too ({@code ck_bank_transaction_*}).
 */
public final class BankTransaction {

    /** Ignore reasons and review notes are at most this long ({@code character varying(500)}). */
    public static final int TEXT_MAX = 500;

    public enum Provider { SEPAY }

    /** How the transfer reached the system: SePay's webhook, or the nightly catch-up through its transaction API. */
    public enum Source { WEBHOOK, CATCH_UP }

    /** Why a transfer waits for the Accountant ({@code ck_bank_transaction_review_reason}); the note says more. */
    public enum ReviewReason {
        /** The content names no installment the way the transfer card writes it. */
        NO_INSTALLMENT_NAMED,
        /** It names more than one: one transfer for several installments is split by the Accountant. */
        SEVERAL_INSTALLMENTS,
        /** It names an installment that does not exist. */
        INSTALLMENT_NOT_FOUND,
        /** The installment is paid or cancelled, or its order is cancelled (15 BR-06: settled outside). */
        NOT_PAYABLE,
        /** More than the installment still owes (BR-05): the Accountant decides where the rest goes. */
        OVERPAYMENT,
        /** Paid into an account other than the one customers are told to pay into. */
        OTHER_ACCOUNT,
        /** The receipt was refused for another reason, which the note gives. */
        REFUSED,
        /** The receipt recorded from it was voided. */
        RECEIPT_VOIDED
    }

    private final UUID id;
    private final Provider provider;
    private final Source source;
    private final BankTransfer transfer;
    private final Instant receivedAt;
    private BankTransactionStatus status;
    private ReviewReason reviewReason;
    private String reviewNote;
    private UUID customerId;
    private UUID installmentId;
    private UUID receiptId;
    private Instant decidedAt;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String ignoreReason;
    private final long version;

    /** Restores one as stored. */
    @SuppressWarnings("java:S107")
    public BankTransaction(UUID id, Provider provider, Source source, BankTransfer transfer, Instant receivedAt,
                           BankTransactionStatus status, ReviewReason reviewReason, String reviewNote,
                           UUID customerId, UUID installmentId, UUID receiptId, Instant decidedAt, UUID reviewedBy,
                           Instant reviewedAt, String ignoreReason, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.source = Objects.requireNonNull(source, "source");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.reviewReason = reviewReason;
        this.reviewNote = reviewNote;
        this.customerId = customerId;
        this.installmentId = installmentId;
        this.receiptId = receiptId;
        this.decidedAt = decidedAt;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.ignoreReason = ignoreReason;
        this.version = version;
    }

    /** A transfer just reported, kept before it is matched. */
    public static BankTransaction received(Provider provider, Source source, BankTransfer transfer, Instant at) {
        return new BankTransaction(Identifiers.newId(), provider, source, transfer, at, BankTransactionStatus.RECEIVED,
                null, null, null, null, null, null, null, null, null, 0L);
    }

    /**
     * A receipt was recorded from it: automatically ({@code by} null, from RECEIVED) or by the Accountant assigning it
     * (from NEEDS_REVIEW).
     *
     * @throws BusinessException {@code BANK_TRANSACTION_NOT_OPEN} when it was decided already
     */
    public void applied(UUID receipt, UUID by, Instant at) {
        Objects.requireNonNull(receipt, "receipt");
        if (by == null ? status != BankTransactionStatus.RECEIVED : status != BankTransactionStatus.NEEDS_REVIEW) {
            throw notOpen();
        }
        this.status = BankTransactionStatus.APPLIED;
        this.receiptId = receipt;
        this.decidedAt = Objects.requireNonNull(at, "at");
        this.reviewReason = null;
        this.reviewNote = null;
        this.reviewedBy = by;
        this.reviewedAt = by == null ? null : at;
    }

    /**
     * Not matched automatically: to the Accountant's queue, with why.
     *
     * @param installment the one installment the content names, when it names one; a hint, null otherwise
     * @param customer    that installment's customer; a hint, null otherwise
     */
    public void needsReview(ReviewReason reason, String note, UUID installment, UUID customer, Instant at) {
        if (status != BankTransactionStatus.RECEIVED) {
            throw notOpen();
        }
        this.status = BankTransactionStatus.NEEDS_REVIEW;
        this.reviewReason = Objects.requireNonNull(reason, "reason");
        this.reviewNote = text(note);
        this.installmentId = installment;
        this.customerId = customer;
        this.decidedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * @throws BusinessException {@code BANK_TRANSACTION_NOT_OPEN} unless it waits for the Accountant
     */
    public void requireReviewable() {
        if (status != BankTransactionStatus.NEEDS_REVIEW) {
            throw notOpen();
        }
    }

    /**
     * The Accountant decided it is no installment's payment here (15 BR-06: it is settled in the accounting software).
     *
     * @throws BusinessException {@code BANK_TRANSACTION_NOT_OPEN} unless it waits for review; {@code VALIDATION_FAILED}
     *         without a reason
     */
    public void ignore(String reason, UUID by, Instant at) {
        if (status != BankTransactionStatus.NEEDS_REVIEW) {
            throw notOpen();
        }
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > TEXT_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Ignoring a transfer says why, in at most %d characters".formatted(TEXT_MAX))
                    .localized("refusal.payment.bankTransactionIgnoreReason", TEXT_MAX);
        }
        this.status = BankTransactionStatus.IGNORED;
        this.reviewReason = null;
        this.ignoreReason = why;
        this.reviewedBy = Objects.requireNonNull(by, "by");
        this.reviewedAt = Objects.requireNonNull(at, "at");
        this.decidedAt = at;
    }

    /** The receipt recorded from it was voided (15 BR-04): the money is assigned again, or ignored. */
    public void receiptVoided(String receiptReference, Instant at) {
        if (status != BankTransactionStatus.APPLIED) {
            return;
        }
        this.status = BankTransactionStatus.NEEDS_REVIEW;
        this.reviewReason = ReviewReason.RECEIPT_VOIDED;
        this.receiptId = null;
        this.reviewedBy = null;
        this.reviewedAt = null;
        this.reviewNote = text("Receipt %s recorded from this transfer was voided: assign it again or ignore it"
                .formatted(receiptReference));
        this.decidedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * The statement reference a receipt recorded from it carries (15 BR-03): the bank's own reference, so neither the
     * feed nor the Accountant records the same money twice; {@code SEPAY-<id>} when the bank gave none.
     */
    public String receiptReference() {
        return transfer.bankReference() != null ? transfer.bankReference()
                : "SEPAY-" + transfer.providerTransactionId();
    }

    public BigDecimal amount() {
        return transfer.amount();
    }

    private static String text(String note) {
        String kept = note == null || note.isBlank() ? "Needs review" : note.strip();
        return kept.length() > TEXT_MAX ? kept.substring(0, TEXT_MAX - 1) + "…" : kept;
    }

    private BusinessException notOpen() {
        return new BusinessException(ErrorCode.BANK_TRANSACTION_NOT_OPEN,
                "Bank transaction %s is %s".formatted(id, status))
                .localized("refusal.payment.bankTransactionNotOpen", status.name());
    }

    public UUID id() { return id; }
    public Provider provider() { return provider; }
    public Source source() { return source; }
    public BankTransfer transfer() { return transfer; }
    public Instant receivedAt() { return receivedAt; }
    public BankTransactionStatus status() { return status; }
    public ReviewReason reviewReason() { return reviewReason; }
    public String reviewNote() { return reviewNote; }
    public UUID customerId() { return customerId; }
    public UUID installmentId() { return installmentId; }
    public UUID receiptId() { return receiptId; }
    public Instant decidedAt() { return decidedAt; }
    public UUID reviewedBy() { return reviewedBy; }
    public Instant reviewedAt() { return reviewedAt; }
    public String ignoreReason() { return ignoreReason; }
    public long version() { return version; }
}
