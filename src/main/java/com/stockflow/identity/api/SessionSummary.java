package com.stockflow.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One signed-in device of a user, for the "where am I signed in" list.
 *
 * @param current true for the session the request itself was made with, so a client can label it
 *                "this device" and never offer to sign it out by mistake
 */
public record SessionSummary(
        UUID sessionId,
        Instant issuedAt,
        Instant expiresAt,
        String clientAddress,
        String userAgent,
        boolean current
) {
}
