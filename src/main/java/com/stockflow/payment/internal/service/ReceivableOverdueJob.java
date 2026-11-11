package com.stockflow.payment.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night at 00:15, installments past their due date become OVERDUE (kltn-docs 15 §4.1 step 4, §4.3 step 4). One
 * set-based UPDATE: running it twice changes nothing more. ShedLock keeps two instances from running it at once. The
 * name and the lock are the ones the money ledger's receivables used (4B-6 reused the job); 4B-10 removed those.
 */
@Component
class ReceivableOverdueJob {

    private final InstallmentService installments;

    ReceivableOverdueJob(InstallmentService installments) {
        this.installments = installments;
    }

    @Scheduled(cron = "${stockflow.payment.overdue-cron:0 15 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "payment.markOverdueReceivables", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void markOverdueReceivables() {
        installments.markOverdue();
    }
}
