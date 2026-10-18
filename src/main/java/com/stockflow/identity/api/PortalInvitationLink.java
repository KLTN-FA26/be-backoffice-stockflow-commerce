package com.stockflow.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A freshly issued invitation, addressed to the account's sign-in e-mail. {@code token} is the raw
 * secret for the e-mail link and exists only in memory; {@link #toString()} leaves it out so a stray log statement cannot leak it.
 */
public record PortalInvitationLink(UUID invitationId, UUID userId, String email, String fullName, String token,
                                   Instant issuedAt, Instant expiresAt) {

    @Override
    public String toString() {
        return "PortalInvitationLink[invitationId=" + invitationId + ", userId=" + userId + ", email=" + email + ", token=REDACTED, issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt + "]";
    }
}
