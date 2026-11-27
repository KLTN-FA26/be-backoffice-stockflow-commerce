package com.stockflow.customer.internal.domain;

import com.stockflow.contracts.SalesOwnerChangeReason;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for a manager's bulk and temporary transfers ({@code customer.sales_owner_transfer_batch} / {@code _item};
 * sales-owner plan BR-S09, §10.1): one batch per client request id, one item per customer moved or skipped.
 */
public interface SalesOwnerTransfers {

    /** Why a customer of a batch was not moved (API contract "Transfer result"). */
    enum Skip {
        /** It was not the source salesperson's when the batch was asked (named by the request, or changed before). */
        NOT_OWNED_BY_SOURCE,
        /** It stopped being the source's while the batch ran (case 9): found so under its row lock. */
        OWNER_CHANGED,
        /** A return: someone moved it on from the temporary owner since (§10.1); the manager decides. */
        REASSIGNED_SINCE,
        /** The request named an id that is no customer. */
        CUSTOMER_NOT_FOUND
    }

    /**
     * A batch.
     *
     * @param fromUserId     the salesperson whose customers move; for a return, the one they go back to
     * @param returnsBatchId for a return, the temporary batch it gives back
     * @param notFound       the ids the request named that are no customer
     */
    record Batch(UUID id, UUID requestId, UUID fromUserId, SalesOwnerChangeReason reason, String note,
                 boolean temporary, UUID returnsBatchId, boolean notifyCustomer, int moved, int skipped,
                 UUID requestedBy, Instant requestedAt, List<UUID> notFound) {

        public Batch {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(fromUserId, "fromUserId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(requestedAt, "requestedAt");
            notFound = notFound == null ? List.of() : List.copyOf(notFound);
        }
    }

    /** One customer of a batch: moved to {@code toUserId}, or skipped for {@code skip}. */
    record Item(UUID customerId, UUID toUserId, Skip skip) {

        public Item {
            Objects.requireNonNull(customerId, "customerId");
            if ((toUserId == null) == (skip == null)) {
                throw new IllegalArgumentException("An item is moved to someone or skipped for a reason");
            }
        }

        public boolean moved() {
            return skip == null;
        }
    }

    /** A batch as the managers' list shows it, with what it still holds and when it was returned. */
    record Summary(Batch batch, List<UUID> toUserIds, int stillHeld, Instant returnedAt) {
    }

    /**
     * Writes {@code batch} unless one with its request id exists. Two requests with one id at once: the second waits
     * for the first to commit and then finds it.
     *
     * @return true when this call wrote it; false when a batch with that request id was already there
     */
    boolean claim(Batch batch);

    /**
     * {@link #claim} for a manager's request, keeping {@code fingerprint} - what the request asked, hashed - so a retry
     * can be told from another request sent with the same id (finding P1).
     */
    boolean claim(Batch batch, String fingerprint);

    /** The fingerprint the request was claimed with; empty for an unknown request or one kept without (a return). */
    Optional<String> fingerprintOf(UUID requestId);

    Optional<Batch> findByRequestId(UUID requestId);

    Optional<Batch> findById(UUID batchId);

    /** The batch under a row lock held to the end of the transaction. */
    Optional<Batch> findForUpdate(UUID batchId);

    /** The return of a temporary batch, if it was returned. */
    Optional<Batch> returnOf(UUID batchId);

    /** Records the batch's items and its counts and not-found ids, in the caller's transaction. */
    void complete(UUID batchId, List<Item> items, List<UUID> notFound);

    /** The batch's items, in customer id order. */
    List<Item> items(UUID batchId);

    /** Batches newest first (returns left out), at most {@code limit}; {@code temporaryOnly} keeps the temporary ones. */
    List<Summary> list(boolean temporaryOnly, int limit);
}
