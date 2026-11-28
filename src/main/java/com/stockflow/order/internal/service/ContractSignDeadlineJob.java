package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night (Saigon), on one instance at a time: announces the sent contracts the customer has not signed by their
 * deadline ({@link ContractSignDeadlineService}). A separate bean so the call goes through the service's transactional
 * proxy, one transaction per batch; the method is public because a non-public {@code @Scheduled} method is not advised
 * by the scheduler lock.
 */
@Component
class ContractSignDeadlineJob {

    /** At most 25 batches (5 000 contracts) a run, well inside the lock's ten minutes. */
    static final int MAX_BATCHES = 25;

    private final ContractSignDeadlineService deadlines;

    ContractSignDeadlineJob(ContractSignDeadlineService deadlines) {
        this.deadlines = deadlines;
    }

    @Scheduled(cron = "${stockflow.order.contract.sign-deadline-cron:0 15 1 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "order.contractSignDeadline", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void announce() {
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            if (deadlines.announceOverdue() < ContractSignDeadlineService.BATCH_SIZE) {
                return;
            }
        }
    }
}
