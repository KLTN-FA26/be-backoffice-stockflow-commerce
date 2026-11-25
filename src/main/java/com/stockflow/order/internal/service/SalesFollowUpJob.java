package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every hour, on one instance at a time: first confirms the orders whose installments on signing are paid but whose
 * event was lost ({@link InstallmentReconciliation}, WF-02 CONC-2), then runs {@link SalesFollowUpService#remind}, so
 * an order confirmed that way is told as confirmed in the same run. A separate bean so the call goes
 * through the service's transactional proxy; the method is public because a non-public {@code @Scheduled} method is not
 * advised by the scheduler lock.
 */
@Component
class SalesFollowUpJob {

    private final SalesFollowUpService followUps;
    private final InstallmentReconciliation reconciliation;

    SalesFollowUpJob(SalesFollowUpService followUps, InstallmentReconciliation reconciliation) {
        this.followUps = followUps;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${stockflow.order.follow-up.interval:PT1H}",
            initialDelayString = "${stockflow.order.follow-up.initial-delay:PT2M}")
    @SchedulerLock(name = "order.salesFollowUp", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    public void remind() {
        reconciliation.confirmPaid();
        followUps.remind();
    }
}
