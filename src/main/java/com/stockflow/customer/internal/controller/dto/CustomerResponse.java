package com.stockflow.customer.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID customerId, UUID userId, String fullName, String email, String phone,
        UUID segmentId, String status, long version, Instant createdAt
) {
}
