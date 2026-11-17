package com.stockflow.payment.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night, tells Sales which installments turned overdue (kltn-docs 15 §4.3 step 11, WF4-OVERDUE-TRIGGER): just
 * after midnight in Vietnam, when the day after a due day has begun and the installment is overdue (§4.1 step 4). One
 * instance at a time ({@code @SchedulerLock}); running it twice, or again after a restart, tells nobody twice - the
 * listener posts once per installment. A separate bean so the call goes through {@link InstallmentService}'s
 * transactional proxy; the method is public because a non-public {@code @Scheduled} method is not advised by the
 * scheduler lock.
 */
@Component
class OverdueInstallmentScanJob {

    /** {@code lockAtMostFor}: far above a pass of a few queries, so the lock never lapses mid-run. */
    static final String LOCK_AT_MOST = "PT30M";

    private final InstallmentService installments;
    private final int lookbackDays;

    OverdueInstallmentScanJob(InstallmentService installments,
                              @Value("${stockflow.payment.overdue-scan.lookback-days:3}") int lookbackDays) {
        this.installments = installments;
        this.lookbackDays = lookbackDays;
    }

    @Scheduled(cron = "${stockflow.payment.overdue-scan.cron:0 20 0 * * *}", zone = BusinessCalendar.ZONE_NAME)
    @SchedulerLock(name = "payment.overdue-installment-scan", lockAtMostFor = LOCK_AT_MOST, lockAtLeastFor = "PT10S")
    public void scan() {
        installments.announceNewlyOverdue(lookbackDays);
    }
}
