package com.stockflow.common.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.UUID;

/**
 * Rejects a token whose session has ended, which is what makes logout, "sign out my other devices",
 * a password change and a role change take effect on the next request instead of at {@code exp}.
 *
 * <p>Runs after the signature and expiry checks, so it only ever sees a token we issued. A token
 * without a {@code sid} claim was issued before sessions existed and is accepted until it expires
 * on its own; every token issued now carries one.</p>
 *
 * <p>The failure is an {@code invalid_token}, so the client gets the ordinary 401 from
 * {@link ApiAuthenticationEntryPoint} and signs in again.</p>
 *
 * <h2>When the session store cannot be read</h2>
 *
 * <p>That is not "the session ended", and must not answer like it. Left to propagate, the database
 * exception escaped the filter chain, the container forwarded to {@code /error}, and that dispatch —
 * anonymous — was answered 401 by the entry point: a database blip signed every user out, with no
 * correlation id to find it by. It is rethrown as a plain {@link JwtException} instead, which Spring
 * maps to an {@code AuthenticationServiceException} and the entry point to a retryable 503 (ADR-0008).
 * Still fail-closed: the request is refused either way, only no longer as a sign-out.</p>
 */
public final class SessionValidator implements OAuth2TokenValidator<Jwt> {

    public static final String SESSION_CLAIM = "sid";

    private static final OAuth2Error ENDED = new OAuth2Error("invalid_token",
            "The session has ended", null);

    private final ActiveSessionCheck sessions;

    public SessionValidator(ActiveSessionCheck sessions) {
        this.sessions = sessions;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String claim = jwt.getClaimAsString(SESSION_CLAIM);
        if (claim == null) {
            return OAuth2TokenValidatorResult.success();
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(claim);
        } catch (IllegalArgumentException malformed) {
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
        boolean active;
        try {
            active = sessions.isActive(sessionId);
        } catch (RuntimeException unavailable) {
            throw new JwtException("The session store is unavailable", unavailable);
        }
        return active ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(ENDED);
    }
}
