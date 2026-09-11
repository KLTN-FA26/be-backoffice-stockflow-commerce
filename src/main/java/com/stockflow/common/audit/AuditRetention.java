package com.stockflow.common.audit;

import com.stockflow.common.audit.persistence.AuditPurger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes audit entries once their retention has passed.
 *
 * <p>Two windows, because {@link AuditAction#isSecurityRelevant()} splits the table into entries
 * that answer "what changed last month" and entries that answer "who had access a year ago". Both
 * defaults are conservative and configurable; check them against whatever retention policy the
 * business actually signs up to before go-live, because this job destroys data permanently.</p>
 *
 * <p>Runs nightly at a quiet hour, in bounded batches for the same reason the idempotency purge
 * does: a year of backlog deleted in one statement would lock the table while it ran.</p>
 */
@Component
class AuditRetention {

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private static final int BATCH_SIZE = 2_000;
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final AuditPurger purger;
    private final Clock clock;
    private final Duration routineRetention;
    private final Duration securityRetention;

    AuditRetention(AuditPurger purger,
                   Clock clock,
                   @Value("${stockflow.audit.routine-retention:P180D}") Duration routineRetention,
                   @Value("${stockflow.audit.security-retention:P730D}") Duration securityRetention) {
        this.purger = purger;
        this.clock = clock;
        this.routineRetention = routineRetention;
        this.securityRetention = securityRetention;
    }

    /**
     * {@code lockAtMostFor} is 30 minutes: a purge that has not finished by then has almost
     * certainly died with the instance, and the lock must not outlive it or the purge stops running
     * altogether. It is also comfortably longer than a real run, which deletes in bounded batches.
     *
     * <p>{@code lockAtLeastFor} is 5 minutes because this job is cron-triggered: two instances whose
     * clocks differ by seconds both fire at 03:30, and without a floor the first could finish and
     * release before the second even asked, letting both run.</p>
     */
    @Scheduled(cron = "${stockflow.audit.purge-cron:0 30 3 * * *}")
    @SchedulerLock(name = "auditRetention.purgeExpired",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void purgeExpired() {
        java.time.Instant now = clock.instant();
        java.time.Instant routineCutoff = now.minus(routineRetention);
        java.time.Instant securityCutoff = now.minus(securityRetention);

        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = purger.purgeBatch(routineCutoff, securityCutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("Purged {} audit entries (routine before {}, security before {})",
                    total, routineCutoff, securityCutoff);
        }
    }
}
