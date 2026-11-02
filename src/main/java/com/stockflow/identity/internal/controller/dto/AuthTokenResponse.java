package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** What the API returns for a successful sign-in. Send back as {@code Authorization: Bearer
 *  <accessToken>} on every subsequent request. */
@Schema(name = "AuthToken", description = "A freshly issued bearer token")
public record AuthTokenResponse(
        String accessToken,

        @Schema(example = "Bearer")
        String tokenType,

        long expiresInSeconds,

        @Schema(description = "The password was set by an administrator: send the user to change it; "
                + "until then every call but GET /me, GET /me/permissions, PUT /auth/password and "
                + "POST /auth/logout answers 403 PASSWORD_CHANGE_REQUIRED")
        boolean mustChangePassword
) {
}
