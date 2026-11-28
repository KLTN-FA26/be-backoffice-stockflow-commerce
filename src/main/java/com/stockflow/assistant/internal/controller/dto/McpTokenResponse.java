package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One of my MCP tokens.
 *
 * @param token  the token itself, only in the answer that made it: it is stored as a hash and shown never again
 * @param hint   its last four characters, to recognise it by
 * @param status LIVE, REVOKED or EXPIRED
 */
public record McpTokenResponse(
        UUID id,
        @Schema(example = "Claude Code on my laptop") String name,
        @Schema(example = "x9Qa") String hint,
        @Schema(example = "LIVE") String status,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        @Schema(description = "Shown once, when the token is made") String token) {
}
