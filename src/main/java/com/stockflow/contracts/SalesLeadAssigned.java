package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>customer</b> when a Sales Manager gave a lead to a Sales (sales-owner plan §10.7); that Sales is
 * told. After commit.
 */
public record SalesLeadAssigned(UUID leadId, UUID salesUserId, Instant at) {

    public SalesLeadAssigned {
        Objects.requireNonNull(leadId, "leadId");
        Objects.requireNonNull(salesUserId, "salesUserId");
        Objects.requireNonNull(at, "at");
    }
}
