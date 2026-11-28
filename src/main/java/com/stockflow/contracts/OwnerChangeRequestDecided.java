package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>customer</b> when a Sales Manager decided a customer's request to change their salesperson
 * (sales-owner plan §10.5 step 4, review finding C1). Approved: the owner has changed in the same transaction (its own
 * {@code CustomerSalesOwnerChanged}, with the customer told in the chat), and both salespeople are told here. Rejected:
 * the customer is told to read the manager's answer on their account page - the answer itself is not in the event, it is
 * text a person typed. Delivered after commit; key {@code requestId}.
 *
 * @param approved        true for APPROVED, false for REJECTED
 * @param previousOwnerId the customer's owner (or former owner) before the decision; null if it never had one
 * @param newOwnerId      the new owner when approved, null when rejected
 */
public record OwnerChangeRequestDecided(UUID requestId, UUID customerId, boolean approved, UUID previousOwnerId,
                                        UUID newOwnerId, Instant at) {

    public OwnerChangeRequestDecided {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(at, "at");
        if (approved == (newOwnerId == null)) {
            throw new IllegalArgumentException("An approval names the new owner; a rejection does not");
        }
    }
}
