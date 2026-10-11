package com.stockflow.customer.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** PENDING / EXPIRED with the open invitation's dates, ACTIVATED, or NO_ACCOUNT for a legacy profile. */
public record PortalInvitationResponse(UUID customerId, String state, Instant issuedAt, Instant expiresAt) {
}
