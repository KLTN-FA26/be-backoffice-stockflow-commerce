package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a password reset returns. */
@Schema(description = "Result of an administrator's password reset")
public record PasswordResetResponse(
        @Schema(description = "Shown once; null when the password was given") String temporaryPassword,
        @Schema(description = "Signed-in devices of the account that were signed out") int sessionsEnded) {
}
