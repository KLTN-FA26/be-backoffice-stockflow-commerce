package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Body of {@code GET /api/v1/identity/me}. */
@Schema(description = "The signed-in user's profile and roles")
public record MeResponse(UUID userId, String username, String email, String fullName, String status,
                         List<String> roles, Instant lastLoginAt) {
}
