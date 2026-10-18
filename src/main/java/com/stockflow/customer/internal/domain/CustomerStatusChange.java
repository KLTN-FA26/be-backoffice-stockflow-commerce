package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One status a customer entered: who, when, and - required when a closed customer is reopened - why.
 * {@code from} is null for the customer's creation.
 */
public record CustomerStatusChange(UUID id, UUID customerId, CustomerStatus from, CustomerStatus to, String reason,
                                   UUID changedBy, Instant changedAt) {

    public CustomerStatusChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(changedAt, "changedAt");
        if (from == to) throw new IllegalArgumentException("A status change changes the status");
        reason = reason == null || reason.isBlank() ? null : reason.strip();
        if (from == CustomerStatus.INACTIVE && reason == null) {
            throw new IllegalArgumentException("Reopening a closed customer needs a reason");
        }
        if (from == CustomerStatus.BLOCKED && to == CustomerStatus.ACTIVE && reason == null) {
            throw new IllegalArgumentException("Lifting a customer's block needs a reason");
        }
    }
}
