package com.stockflow.common.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

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
        try {
            return sessions.isActive(UUID.fromString(claim))
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(ENDED);
        } catch (IllegalArgumentException malformed) {
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
    }
}
