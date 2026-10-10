package com.stockflow.identity.internal.service;

import com.stockflow.identity.internal.domain.UserId;
import com.stockflow.identity.internal.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Counts wrong passwords and locks an account after too many in a row (SCRUM-456).
 *
 * <p>A separate bean with its own transaction, because the sign-in that calls it fails: the
 * {@code UNAUTHORIZED} it throws rolls back the sign-in's transaction, and a count written there
 * would be rolled back with it — the lockout would never trigger.</p>
 *
 * <p>The IP rate limit on the login endpoint slows one attacker down; this protects one account
 * from many addresses.</p>
 */
@Component
class LoginAttempts {

    private static final Logger log = LoggerFactory.getLogger(LoginAttempts.class);

    private final UserRepository users;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration lockFor;

    LoginAttempts(UserRepository users, Clock clock,
                  @Value("${stockflow.security.login.max-failed-attempts:5}") int maxAttempts,
                  @Value("${stockflow.security.login.lock-duration:PT15M}") Duration lockFor) {
        this.users = users;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.lockFor = lockFor;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UserId userId) {
        users.findById(userId).ifPresent(user -> {
            if (user.recordFailedSignIn(clock.instant(), maxAttempts, lockFor)) {
                log.warn("Account {} locked for {} after {} wrong passwords in a row", userId.value(), lockFor,
                        maxAttempts);
            }
            users.save(user);
        });
    }
}
