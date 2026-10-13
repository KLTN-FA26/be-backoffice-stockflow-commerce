package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night at 02:00 business time (flow F-EXP): moves expired stock to EXPIRED and raises the expiry alerts. The
 * work is {@link ExpirySweeper}; this only schedules it. 02:00 sits between the 00:15 receivable job, the 01:30 alert
 * reconcile and the 03:30 audit purge. ShedLock keeps two instances from sweeping at once.
 */
@Component
class ExpirySweepJob {

    private final ExpirySweeper sweeper;

    ExpirySweepJob(ExpirySweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(cron = "${stockflow.inventory.expiry-sweep-cron:0 0 2 * * *}", zone = BusinessCalendar.ZONE_NAME)
    @SchedulerLock(name = "inventory.expirySweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void sweep() {
        sweeper.sweep();
    }
}
