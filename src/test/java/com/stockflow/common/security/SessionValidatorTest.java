package com.stockflow.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionValidatorTest {

    private final ActiveSessionCheck sessions = mock(ActiveSessionCheck.class);
    private final SessionValidator validator = new SessionValidator(sessions);

    private static Jwt token(String sid) {
        var builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (sid != null) {
            builder.claim(SessionValidator.SESSION_CLAIM, sid);
        }
        return builder.build();
    }

    @Test
    void acceptsATokenWhoseSessionIsLive() {
        UUID sid = UUID.randomUUID();
        when(sessions.isActive(sid)).thenReturn(true);

        assertThat(validator.validate(token(sid.toString())).hasErrors()).isFalse();
    }

    @Test
    void refusesATokenWhoseSessionHasEnded() {
        UUID sid = UUID.randomUUID();
        when(sessions.isActive(sid)).thenReturn(false);

        var result = validator.validate(token(sid.toString()));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.getErrorCode()).isEqualTo("invalid_token");
            assertThat(e.getDescription()).isEqualTo("The session has ended");
        });
    }

    @Test
    void refusesAMalformedSessionClaimInsteadOfThrowing() {
        assertThat(validator.validate(token("not-a-uuid")).hasErrors()).isTrue();
        verifyNoInteractions(sessions);
    }

    @Test
    void acceptsATokenIssuedBeforeSessionsExistedWithoutAsking() {
        assertThat(validator.validate(token(null)).hasErrors()).isFalse();
        verifyNoInteractions(sessions);
    }

    @Test
    void anUnreadableSessionStoreIsAServiceErrorNotAnEndedSession() {
        UUID sid = UUID.randomUUID();
        when(sessions.isActive(sid)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

        // A plain JwtException (not BadJwtException) becomes AuthenticationServiceException -> 503.
        // Returning a failure result instead would be a 401, and the frontend would sign the user out.
        assertThatThrownBy(() -> validator.validate(token(sid.toString())))
                .isExactlyInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }
}
