package com.stockflow.customer.internal.domain;

import com.stockflow.customer.api.SalesOwnerState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's Sales owner as stored (sales-owner plan §2.1). The state and the columns agree, as
 * {@code ck_customer_sales_owner_consistent} says in the database: UNASSIGNED has neither owner nor date, ASSIGNED and
 * NEEDS_ASSIGNMENT have both (NEEDS_ASSIGNMENT keeps the owner who left).
 *
 * @param userId the owner, or the one who left; null only when UNASSIGNED
 * @param since  when that owner was assigned; null only when UNASSIGNED
 */
public record SalesOwner(UUID userId, SalesOwnerState state, Instant since) {

    private static final SalesOwner NONE = new SalesOwner(null, SalesOwnerState.UNASSIGNED, null);

    public SalesOwner {
        Objects.requireNonNull(state, "state");
        boolean unassigned = state == SalesOwnerState.UNASSIGNED;
        if (unassigned != (userId == null) || unassigned != (since == null)) {
            throw new IllegalArgumentException("A sales owner's state must agree with its owner and date: " + state);
        }
    }

    public static SalesOwner unassigned() {
        return NONE;
    }

    /** Whether {@code userId} is the assigned owner (not one who left). */
    public boolean isAssignedTo(UUID candidate) {
        return state == SalesOwnerState.ASSIGNED && userId.equals(candidate);
    }
}
