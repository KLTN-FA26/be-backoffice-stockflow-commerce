package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One account in user management. Never carries credentials. */
@Schema(name = "User", description = "An account as user management sees it")
public record UserResponse(
        UUID userId,
        String username,
        String email,
        String fullName,
        @Schema(example = "ACTIVE", allowableValues = {"ACTIVE", "LOCKED", "DISABLED"}) String status,
        List<String> roles,
        @Schema(description = "The password was set by an administrator and must be changed")
        boolean mustChangePassword,
        @Schema(description = "Set while a lock after too many wrong passwords runs")
        Instant lockedUntil,
        Instant lastLoginAt,
        Instant createdAt,
        String createdBy,
        @Schema(description = "Send it back with an edit") long version) {
}
