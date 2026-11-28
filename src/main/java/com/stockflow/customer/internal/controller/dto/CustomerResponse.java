package com.stockflow.customer.internal.controller.dto;

import com.stockflow.customer.api.SalesOwnerState;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer. The owner fields (sales-owner plan, API contract: list rows) are filled on the staff list and detail and
 * null elsewhere (a portal account reads its salesperson from {@code /customers/me/sales-owner}, names only).
 *
 * @param salesOwnerActive whether the owner's account works now; null when the customer has no owner
 * @param taxCodeMissing   owner decision D41: an existing customer without a tax code is flagged, not forced
 */
public record CustomerResponse(
        UUID customerId, UUID userId, String fullName, String email, String phone, String taxCode,
        UUID segmentId, String status, long version, Instant createdAt,
        UUID salesOwnerId, String salesOwnerName, Boolean salesOwnerActive, SalesOwnerState salesOwnerState,
        Instant salesOwnerSince, boolean taxCodeMissing
) {
}
