package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A personal token for the MCP endpoint as its owner sees it in their list: never the token itself, which is shown
 * once when made and stored only as a hash.
 *
 * @param hint the token's last four characters, to recognise it by
 */
public record McpToken(UUID id, String name, String hint, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
                       Instant revokedAt) {

    /** Whether the token still opens the endpoint at {@code now}: not revoked and not expired. */
    public boolean live(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }
}
