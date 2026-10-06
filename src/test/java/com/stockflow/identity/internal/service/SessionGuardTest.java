package com.stockflow.identity.internal.service;

import com.stockflow.identity.internal.domain.SessionEndReason;
import com.stockflow.identity.internal.entity.UserSessionJpaEntity;
import com.stockflow.identity.internal.repository.UserSessionJpaRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionGuardTest {

    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    private final UserSessionJpaRepository sessions = mock(UserSessionJpaRepository.class);
    private final SessionGuard guard = new SessionGuard(sessions, Clock.fixed(NOW, ZoneOffset.UTC));

    private UserSessionJpaEntity session(Instant expiresAt) {
        var s = new UserSessionJpaEntity(UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(3600), expiresAt, null, null);
        when(sessions.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void aLiveSessionIsActive() {
        assertThat(guard.isActive(session(NOW.plusSeconds(1)).getId())).isTrue();
    }

    @Test
    void aRevokedSessionIsNot() {
        var s = session(NOW.plusSeconds(3600));
        s.revoke(NOW, SessionEndReason.LOGOUT);

        assertThat(guard.isActive(s.getId())).isFalse();
    }

    @Test
    void anExpiredSessionIsNotEvenIfNeverRevoked() {
        assertThat(guard.isActive(session(NOW).getId())).as("expiry is exclusive").isFalse();
        assertThat(guard.isActive(session(NOW.minusSeconds(1)).getId())).isFalse();
    }

    @Test
    void anUnknownSessionIsNot() {
        assertThat(guard.isActive(UUID.randomUUID())).isFalse();
    }
}
