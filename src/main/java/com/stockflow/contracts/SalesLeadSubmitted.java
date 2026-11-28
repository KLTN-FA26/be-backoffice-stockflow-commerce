package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>customer</b> when a business asked to open an account through the public form (sales-owner plan
 * §10.3, channel B) and a lead was created. The Sales Managers are told; they assign it. After commit.
 */
public record SalesLeadSubmitted(UUID leadId, Instant at) {

    public SalesLeadSubmitted {
        Objects.requireNonNull(leadId, "leadId");
        Objects.requireNonNull(at, "at");
    }
}
