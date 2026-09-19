package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One signed-in device of the caller")
public record SessionResponse(
        UUID sessionId,
        Instant issuedAt,
        Instant expiresAt,
        @Schema(description = "Address the sign-in came from") String clientAddress,
        @Schema(description = "The client's own description of itself") String userAgent,
        @Schema(description = "True for the session this request was made with") boolean current
) {
}
