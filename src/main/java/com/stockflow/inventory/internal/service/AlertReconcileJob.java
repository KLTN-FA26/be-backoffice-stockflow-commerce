package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night, evaluates every SKU that is low or still has an open stock alert: the net under the dirty queue
 * (a missed trigger, a restore from backup, a threshold edited with SQL). 01:30 business time, before the 02:00
 * expiry sweep.
 */
@Component
class AlertReconcileJob {

    private final AlertReconciler reconciler;

    AlertReconcileJob(AlertReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(cron = "${stockflow.inventory.alert-reconcile-cron:0 30 1 * * *}", zone = BusinessCalendar.ZONE_NAME)
    @SchedulerLock(name = "inventory.reconcileAlerts", lockAtMostFor = "PT20M", lockAtLeastFor = "PT30S")
    public void reconcile() {
        reconciler.reconcile();
    }
}
