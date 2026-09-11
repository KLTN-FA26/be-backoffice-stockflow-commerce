package com.stockflow.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Deletes idempotency records past their retention.
 *
 * <p>Without it the table grows by one row per idempotent request, for ever — a table nobody looks
 * at until its index no longer fits in memory and every write slows down. Retention is 24 hours
 * (see {@link IdempotencyFilter}), so this only ever removes rows that can no longer be replayed.</p>
 *
 * <p>Hourly, in bounded batches, and it keeps deleting while a batch comes back full — so a backlog
 * after an outage drains over successive passes rather than in one statement that locks the table.
 * The loop is capped so a runaway cannot occupy the scheduler thread indefinitely.</p>
 */
@Component
class IdempotencyHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyHousekeeping.class);

    private static final int BATCH_SIZE = 1_000;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final IdempotencyStore store;
    private final Clock clock;

    IdempotencyHousekeeping(IdempotencyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Hourly and bounded, so 10 minutes is far more than a run needs; the floor is 1 minute because
     * an hourly cron gives clock-skewed instances a wide window to trip over each other.
     */
    @Scheduled(cron = "${stockflow.idempotency.purge-cron:0 15 * * * *}")
    @SchedulerLock(name = "idempotency.purgeExpired",
            lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purgeExpired() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = store.deleteExpired(clock.instant(), BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("Purged {} expired idempotency record(s)", total);
        }
    }
}
