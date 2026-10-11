package com.stockflow.identity.api;

import java.time.Instant;

/** When the open invitation of an account was sent and until when its link works. No secret. */
public record PortalInvitationStatus(Instant issuedAt, Instant expiresAt) {

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
