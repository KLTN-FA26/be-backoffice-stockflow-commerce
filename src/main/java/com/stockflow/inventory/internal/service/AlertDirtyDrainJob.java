package com.stockflow.inventory.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 30 seconds, evaluates the SKUs whose stock changed since the last run (plan G5a-3). A SKU written a hundred
 * times in a minute is evaluated once. The queue is fed by triggers on {@code stock_item}, so every writer is covered,
 * including those that do not exist yet.
 *
 * <p>ShedLock keeps two instances from draining at once; the lock is short because a run is a few queries.</p>
 */
@Component
class AlertDirtyDrainJob {

    private final AlertDirtyDrainer drainer;

    AlertDirtyDrainJob(AlertDirtyDrainer drainer) {
        this.drainer = drainer;
    }

    @Scheduled(fixedDelayString = "${stockflow.inventory.alert-drain-interval:PT30S}",
            initialDelayString = "${stockflow.inventory.alert-drain-initial-delay:PT30S}")
    @SchedulerLock(name = "inventory.drainAlertDirty", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void drain() {
        drainer.drain();
    }
}
