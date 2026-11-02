package com.stockflow.identity.api;

/**
 * Output of {@link IdentityService#login}: a bearer token ready to send as
 * {@code Authorization: Bearer <accessToken>}.
 *
 * @param mustChangePassword the password is an administrator-set one: until it is replaced the server
 *                           refuses everything but a handful of calls (403 {@code PASSWORD_CHANGE_REQUIRED})
 */
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds,
                            boolean mustChangePassword) {
}
