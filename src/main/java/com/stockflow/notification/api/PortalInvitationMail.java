package com.stockflow.notification.api;

import java.time.Instant;

/**
 * One portal invitation e-mail. {@code activationUrl} carries the invitation secret, so
 * {@link #toString()} leaves it out and nothing here may be persisted or logged.
 */
public record PortalInvitationMail(String recipient, String contactName, String companyName,
                                   String activationUrl, Instant expiresAt) {

    @Override
    public String toString() {
        return "PortalInvitationMail[recipient=" + recipient + ", activationUrl=REDACTED, expiresAt="
                + expiresAt + "]";
    }
}
