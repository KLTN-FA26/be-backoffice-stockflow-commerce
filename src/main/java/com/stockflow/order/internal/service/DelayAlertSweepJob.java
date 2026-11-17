package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The nightly sweep of the delay alerts (4A-7, kltn-docs 17 §4.12 step 48: "quét mỗi đêm"): most conditions start or
 * stop holding because a day passed, with no operation to recompute after. Every followed sales order is recomputed,
 * each in its own transaction, so one failing order neither stops the rest nor rolls them back. One instance at a time
 * ({@code @SchedulerLock}); running it twice opens nothing twice (the recompute is idempotent and open alerts are unique
 * per type and subject).
 *
 * <p><b>How long it may take.</b> One pass over every followed order, sequentially: a few queries per order, so seconds
 * for hundreds of orders. The lock is held at most {@value #LOCK_AT_MOST} - far above that - because a lock that lapses
 * mid-run lets a second instance start the same pass. Each run logs how long it took, and warns once it used half of
 * that bound, long before a second instance could start beside it.</p>
 */
@Component
class DelayAlertSweepJob {

    private static final Logger log = LoggerFactory.getLogger(DelayAlertSweepJob.class);

    /** {@code lockAtMostFor}: a run longer than this could overlap the next instance's. */
    static final String LOCK_AT_MOST = "PT2H";
    private static final Duration WARN_AFTER = Duration.parse(LOCK_AT_MOST).dividedBy(2);

    /** What one pass did, and how long it took. */
    record Summary(int orders, int opened, int closed, int failed, Duration elapsed) {
    }

    private final DelayAlertService alerts;
    /** One transaction per order: the recompute joins it, and a failure rolls back that order only. */
    private final TransactionTemplate eachOrder;
    private final Clock clock;

    DelayAlertSweepJob(DelayAlertService alerts, PlatformTransactionManager transactions, Clock clock) {
        this.alerts = alerts;
        this.eachOrder = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** Just after midnight in Vietnam, when the day that makes something late has begun. */
    @Scheduled(cron = "${stockflow.production-schedule.alert-sweep-cron:0 5 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "order.delay-alert-sweep", lockAtMostFor = LOCK_AT_MOST, lockAtLeastFor = "PT10S")
    public void sweep() {
        run();
    }

    /** The pass itself, under the caller's lock. */
    Summary run() {
        Instant started = clock.instant();
        int orders = 0;
        int opened = 0;
        int closed = 0;
        int failed = 0;
        for (UUID salesOrderId : alerts.sweepCandidates()) {
            try {
                DelayAlertService.Recomputed result = eachOrder.execute(status -> alerts.recompute(salesOrderId));
                opened += result.opened();
                closed += result.closed();
                orders++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Delay alerts of order {} were not recomputed: {}", salesOrderId, e.toString());
            }
        }
        Duration elapsed = Duration.between(started, clock.instant());
        log.info("Delay alert sweep: {} order(s), {} opened, {} closed, {} failed in {} ms", orders, opened, closed,
                failed, elapsed.toMillis());
        if (elapsed.compareTo(WARN_AFTER) > 0) {
            log.warn("Delay alert sweep took {} of the {} its lock allows: raise lockAtMostFor or split the pass",
                    elapsed, LOCK_AT_MOST);
        }
        return new Summary(orders, opened, closed, failed, elapsed);
    }
}
