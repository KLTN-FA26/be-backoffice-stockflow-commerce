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
        UUID segmentId,
        String status,
        long version,
        Instant createdAt
) {
}
