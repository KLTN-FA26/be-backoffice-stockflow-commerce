package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>customer</b> when a customer asked, from the portal, to be served by another Sales (sales-owner plan
 * §10.5). Only the Sales Managers are told: the current owner does not see the request until it is decided. After
 * commit.
 */
public record OwnerChangeRequested(UUID requestId, UUID customerId, Instant at) {

    public OwnerChangeRequested {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(at, "at");
    }
}
