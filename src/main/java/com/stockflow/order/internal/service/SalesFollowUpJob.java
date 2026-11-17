package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link SalesFollowUpService#remind} every hour, on one instance at a time. A separate bean so the call goes
 * through the service's transactional proxy; the method is public because a non-public {@code @Scheduled} method is not
 * advised by the scheduler lock.
 */
@Component
class SalesFollowUpJob {

    private final SalesFollowUpService followUps;

    SalesFollowUpJob(SalesFollowUpService followUps) {
        this.followUps = followUps;
    }

    @Scheduled(fixedDelayString = "${stockflow.order.follow-up.interval:PT1H}",
            initialDelayString = "${stockflow.order.follow-up.initial-delay:PT2M}")
    @SchedulerLock(name = "order.salesFollowUp", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    public void remind() {
        followUps.remind();
    }
}
