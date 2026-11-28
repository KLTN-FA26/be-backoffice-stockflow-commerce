package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: the PDF each revision of a contract was sent as, and what the customer asked for after reading it. Written when
 * the contract is sent (the revision's row) and when the customer asks for changes (the same row, once); never deleted.
 */
public interface SalesContractRevisions {

    /**
     * One revision as it was sent.
     *
     * @param changeRequestReason what the customer asked for; null while the revision stands (or ended another way)
     */
    record Revision(UUID contractId, int revision, String documentKey, Instant sentAt, UUID sentBy,
                    String changeRequestReason, Instant changeRequestedAt, UUID changeRequestedBy) {
    }

    /** A contract past its signing deadline whose revision was just marked as told. */
    record Overdue(UUID contractId, String contractNumber, UUID orderId, String orderNumber, UUID customerId,
                   int revision, LocalDate signBy) {
    }

    /** Records the revision sent; the same revision recorded again changes nothing. */
    void recordSent(UUID contractId, SentRevision sent);

    /** The customer's change request ends the revision; recorded once. */
    void recordChangeRequest(UUID contractId, int revision, String reason, Instant at, UUID by);

    /** Every revision of the contract, oldest first. */
    List<Revision> of(UUID contractId);

    Optional<Revision> find(UUID contractId, int revision);

    /**
     * Marks, once per contract revision, the SENT contracts whose deadline is before {@code today} as told, and answers
     * them: the nightly "deadline passed" notice. At most {@code limit}; the rest are marked by the next run. A revision
     * marked is never marked again, so a contract extended and overdue again is not told twice.
     */
    List<Overdue> claimOverdue(LocalDate today, Instant at, int limit);
}
