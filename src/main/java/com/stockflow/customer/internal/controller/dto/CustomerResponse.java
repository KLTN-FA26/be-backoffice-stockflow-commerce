package com.stockflow.customer.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID customerId, UUID userId, String fullName, String email, String phone, String taxCode,
        UUID segmentId, String status, long version, Instant createdAt,
        @Schema(description = "Province of the customer's default shipping address, to tell customers of the same "
                + "name apart. Only on the staff list (GET /customers), and only for a caller who may read "
                + "addresses (customer-addresses:READ); absent for a customer with no shipping address",
                nullable = true) String defaultShippingProvinceName
) {

    /** Without the province: every response but a row of the staff list. */
    public CustomerResponse(UUID customerId, UUID userId, String fullName, String email, String phone,
                            String taxCode, UUID segmentId, String status, long version, Instant createdAt) {
        this(customerId, userId, fullName, email, phone, taxCode, segmentId, status, version, createdAt, null);
    }
}
