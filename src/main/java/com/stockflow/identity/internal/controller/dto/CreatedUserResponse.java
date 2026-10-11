package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** What {@code POST /api/v1/identity/users} returns. */
@Schema(description = "The new account, and its generated password if one was generated")
public record CreatedUserResponse(
        UserResponse user,
        @Schema(description = "Shown once, never stored in clear; null when the password was given")
        String temporaryPassword) {
}
