package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The nightly sweep of the delay alerts (4A-7, kltn-docs 17 §4.12 step 48: "quét mỗi đêm"): most conditions start or
 * stop holding because a day passed, with no operation to recompute after. Every followed sales order is recomputed,
 * each in its own transaction, so one failing order neither stops the rest nor rolls them back. One instance at a time
 * ({@code @SchedulerLock}); running it twice opens nothing twice (the recompute is idempotent and open alerts are unique
 * per type and subject).
 */
@Component
class DelayAlertSweepJob {

    private static final Logger log = LoggerFactory.getLogger(DelayAlertSweepJob.class);

    private final DelayAlertService alerts;

    DelayAlertSweepJob(DelayAlertService alerts) {
        this.alerts = alerts;
    }

    /** Just after midnight in Vietnam, when the day that makes something late has begun. */
    @Scheduled(cron = "${stockflow.production-schedule.alert-sweep-cron:0 5 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "order.delay-alert-sweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    public void sweep() {
        int orders = 0;
        int opened = 0;
        int closed = 0;
        int failed = 0;
        for (UUID salesOrderId : alerts.sweepCandidates()) {
            try {
                DelayAlertService.Recomputed result = alerts.recompute(salesOrderId);
                opened += result.opened();
                closed += result.closed();
                orders++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Delay alerts of order {} were not recomputed: {}", salesOrderId, e.toString());
            }
        }
        log.info("Delay alert sweep: {} order(s), {} opened, {} closed, {} failed", orders, opened, closed, failed);
    }
}
