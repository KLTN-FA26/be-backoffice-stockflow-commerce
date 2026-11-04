package com.stockflow.customer.api;

import java.time.Instant;
import java.util.UUID;

/** Cross-module customer profile view. */
public record CustomerSummary(
        UUID customerId,
        UUID userId,
        String fullName,
        String email,
        String phone,
        /* Normalised tax code; null for a customer created before SCRUM-450. */
        String taxCode,
        UUID segmentId,
        String status,
        long version,
        Instant createdAt
) {
}
