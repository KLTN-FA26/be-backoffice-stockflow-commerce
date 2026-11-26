package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the submissions of contracts to accounting before they are sent
 * ({@code ordering.contract_credit_review}, kltn-docs 15 BR-11 as changed on 2026-11-26): one row per submission, with
 * the credit figures decided on kept as they were. Joins the caller's transaction; the order's row lock serialises every
 * write of one contract's reviews. A contract cancelled while one waits takes it with it: the contract's adapter closes
 * it WITHDRAWN with the cancellation's reason.
 */
public interface ContractCreditReviews {

    enum Outcome { PENDING, APPROVED, REJECTED, WITHDRAWN }

    /**
     * @param contractVersion the version of the contract Sales sent for review: what was decided on
     * @param note            the approver's note, the reason it was refused, or why it was withdrawn; null when none
     */
    record Review(UUID id, UUID contractId, long contractVersion, Outcome outcome, ContractCreditFigures figures,
                  Instant submittedAt, UUID submittedBy, Instant decidedAt, UUID decidedBy, String note) {
    }

    /**
     * A review waiting, for accounting's queue and the reminders.
     *
     * @param contractVersion the contract's version now - what an approval or a refusal is sent with
     */
    record Waiting(UUID reviewId, UUID contractId, String contractNumber, long contractVersion, UUID orderId,
                   String orderNumber, UUID customerId, ContractCreditFigures figures, UUID submittedBy,
                   Instant submittedAt) {
    }

    /** Opens the contract's waiting review; there is at most one ({@code uk_contract_credit_review_pending}). */
    Review open(UUID contractId, long contractVersion, ContractCreditFigures figures, UUID by, Instant at);

    /** The contract's review still waiting, if any. */
    Optional<Review> pending(UUID contractId);

    /** The contract's latest review, whatever became of it. */
    Optional<Review> latest(UUID contractId);

    /** The contract's approved review, if any: approving sends the contract, so there is at most one. */
    Optional<Review> approved(UUID contractId);

    /** Closes a waiting review; {@code note} is required for APPROVED and REJECTED. */
    void close(UUID reviewId, Outcome outcome, UUID by, Instant at, String note);

    /** The reviews waiting, the oldest first: {@code limit} of them from {@code offset}. */
    List<Waiting> waiting(long offset, int limit);

    /** How many reviews wait. */
    long countWaiting();

    /** The reviews waiting since {@code submittedBy} or before, the newest first, at most {@code limit}. */
    List<Waiting> waitingSince(Instant submittedBy, int limit);
}
