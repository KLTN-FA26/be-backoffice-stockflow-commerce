package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One change of who the customer is on paper - its company name, its tax code - with the value before and after, who
 * made it and when (decision 2026-10-17: the customer's contact may rename the company, and every change is kept).
 */
public record CustomerChange(UUID id, UUID customerId, Field field, String oldValue, String newValue, UUID changedBy,
                             Instant changedAt) {

    /** What changed. */
    public enum Field { FULL_NAME, TAX_CODE }

    public CustomerChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(changedAt, "changedAt");
        if (Objects.equals(oldValue, newValue)) throw new IllegalArgumentException("A change changes the value");
    }
}
