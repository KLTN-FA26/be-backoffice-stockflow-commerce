package com.stockflow.identity.internal.service;

import com.stockflow.identity.internal.repository.UserSessionJpaRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes sessions a day after their token expired. They stop mattering the moment they expire; the
 * day is only so support can answer "was I signed in from there yesterday".
 */
@Component
class SessionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(SessionPurgeJob.class);
    private static final Duration RETENTION_AFTER_EXPIRY = Duration.ofDays(1);

    private final UserSessionJpaRepository sessions;
    private final Clock clock;

    SessionPurgeJob(UserSessionJpaRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${stockflow.identity.session-purge-interval:PT1H}")
    @SchedulerLock(name = "identity.purgeExpiredSessions", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    public void purgeExpiredSessions() {
        int removed = sessions.deleteExpiredBefore(clock.instant().minus(RETENTION_AFTER_EXPIRY));
        if (removed > 0) {
            log.info("Purged {} expired sessions", removed);
        }
    }
}
