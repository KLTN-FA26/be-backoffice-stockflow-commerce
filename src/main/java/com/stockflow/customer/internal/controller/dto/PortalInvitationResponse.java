package com.stockflow.customer.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * PENDING / EXPIRED with the open invitation's dates and the outcome of its e-mail (SENT, FAILED, or
 * null when none was recorded), ACTIVATED, or NO_ACCOUNT for a legacy profile without a portal account.
 */
public record PortalInvitationResponse(UUID customerId, String state, Instant issuedAt, Instant expiresAt,
                                       String delivery) {
}
