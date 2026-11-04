package com.stockflow.identity.api;

import java.time.Instant;

/**
 * The open invitation of an account: when it was sent, until when its link works, and whether the
 * e-mail went out ({@code SENT}, {@code FAILED}, or null when no outcome was recorded). No secret.
 */
public record PortalInvitationStatus(Instant issuedAt, Instant expiresAt, String delivery) {

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
