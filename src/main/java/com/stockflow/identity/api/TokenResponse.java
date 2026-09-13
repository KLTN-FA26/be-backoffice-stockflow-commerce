package com.stockflow.identity.api;

/** Output of {@link IdentityService#login}: a bearer token ready to send as
 *  {@code Authorization: Bearer <accessToken>}. */
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
