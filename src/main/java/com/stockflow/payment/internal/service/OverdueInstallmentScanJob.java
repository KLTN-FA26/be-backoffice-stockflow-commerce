package com.stockflow.payment.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night, tells Sales which installments are overdue and were not told for their current due date (kltn-docs 15
 * §4.3 step 11, WF4-OVERDUE-TRIGGER, WF-05 BE-49): just after midnight in Vietnam, when the day after a due day has
 * begun and the installment is overdue (§4.1 step 4). One instance at a time ({@code @SchedulerLock}); running it twice,
 * or again after a restart, tells nobody twice - the scan writes down the due date it announced, and the listener posts
 * once per installment and due date. A separate bean so the call goes through {@link InstallmentService}'s
 * transactional proxy; the method is public because a non-public {@code @Scheduled} method is not advised by the
 * scheduler lock.
 *
 * <p>There is no look-back any more ({@code stockflow.payment.overdue-scan.lookback-days} is no longer read): what was
 * announced is recorded, so a night the job missed is caught up whatever its length.</p>
 */
@Component
class OverdueInstallmentScanJob {

    /** {@code lockAtMostFor}: far above a pass of a few queries, so the lock never lapses mid-run. */
    static final String LOCK_AT_MOST = "PT30M";

    private final InstallmentService installments;

    OverdueInstallmentScanJob(InstallmentService installments) {
        this.installments = installments;
    }

    @Scheduled(cron = "${stockflow.payment.overdue-scan.cron:0 20 0 * * *}", zone = BusinessCalendar.ZONE_NAME)
    @SchedulerLock(name = "payment.overdue-installment-scan", lockAtMostFor = LOCK_AT_MOST, lockAtLeastFor = "PT10S")
    public void scan() {
        installments.announceNewlyOverdue();
    }
}
