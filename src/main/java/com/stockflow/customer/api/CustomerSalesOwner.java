package com.stockflow.customer.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer's stored Sales owner, as {@link SalesOwnership#ownerOf} reads it.
 *
 * @param salesOwnerId the owner; for NEEDS_ASSIGNMENT the one who left; null for UNASSIGNED
 * @param since        when the owner was assigned; null for UNASSIGNED
 * @param version      the customer's version, to send back with a change (optimistic locking, case 15)
 */
public record CustomerSalesOwner(UUID customerId, UUID salesOwnerId, SalesOwnerState state, Instant since,
                                 long version) {
}
