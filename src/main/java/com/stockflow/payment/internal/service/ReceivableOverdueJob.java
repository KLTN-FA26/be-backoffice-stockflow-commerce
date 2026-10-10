package com.stockflow.payment.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night, receivables past their due date become OVERDUE (kltn-docs 15 §4.3 step 4; SCRUM-431).
 * One set-based UPDATE: running it twice changes nothing more. ShedLock keeps two instances from
 * running it at once.
 */
@Component
class ReceivableOverdueJob {

    private final ReceivableService receivables;

    ReceivableOverdueJob(ReceivableService receivables) {
        this.receivables = receivables;
    }

    @Scheduled(cron = "${stockflow.payment.overdue-cron:0 15 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "payment.markOverdueReceivables", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void markOverdueReceivables() {
        receivables.markOverdue();
    }
}
