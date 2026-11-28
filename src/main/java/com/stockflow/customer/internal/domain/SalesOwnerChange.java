package com.stockflow.customer.internal.domain;

import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.SalesOwnerState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One change of a customer's Sales owner, as the aggregate decided it: the row the history keeps
 * ({@code customer.customer_sales_owner_history}, append-only) and what the change event says.
 *
 * @param fromUserId the owner before (or the one who left), null when the customer had none
 * @param toUserId   the owner after; null when the customer was marked NEEDS_ASSIGNMENT
 * @param changedBy  who did it, null for the system
 * @param batchId    the transfer batch, null for a single change
 */
public record SalesOwnerChange(UUID id, UUID customerId, UUID fromUserId, UUID toUserId, SalesOwnerState toState,
                               SalesOwnerChangeReason reason, String note, UUID changedBy, Instant changedAt,
                               UUID batchId) {

    public SalesOwnerChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(toState, "toState");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(changedAt, "changedAt");
        boolean valid = switch (toState) {
            case ASSIGNED -> toUserId != null;
            case NEEDS_ASSIGNMENT -> toUserId == null && fromUserId != null;
            case UNASSIGNED -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("A change either gives an owner or marks one as gone, not " + toState);
        }
    }
}
