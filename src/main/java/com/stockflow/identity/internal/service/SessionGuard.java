package com.stockflow.identity.internal.service;

import com.stockflow.common.security.ActiveSessionCheck;
import com.stockflow.identity.internal.repository.UserSessionJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Answers the resource server's per-request question "is this token's session still live". A
 * primary-key read with no caching on purpose: a cache would delay exactly the thing a user asked for
 * when they signed a device out.
 *
 * <p>It loads the row by id and judges revocation and expiry in Java, rather than asking the database
 * "exists where id = ? and not revoked and not expired". With those extra predicates the planner is
 * free to pick a partial or date index instead of the primary key, which it does on a table
 * dominated by expired rows; a plain id lookup cannot be planned any other way.</p>
 */
@Component
class SessionGuard implements ActiveSessionCheck {

    private final UserSessionJpaRepository sessions;
    private final Clock clock;

    SessionGuard(UserSessionJpaRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID sessionId) {
        return sessions.findById(sessionId).map(session -> session.isLiveAt(clock.instant())).orElse(false);
    }
}
