package com.stockflow.order.internal.service;

import com.stockflow.contracts.ProductionCompleted;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.order.internal.domain.ProductionRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Keeps the order module's own record of what production did (SCRUM-423). Asynchronous and after
 * commit, like the payment listener: production's fact stands whatever the order does with it, and a
 * failure here is retried from the event publication registry. Both events are recorded idempotently,
 * keyed by the producer's id, so a redelivered event changes nothing.
 */
@Component("orderProductionEventListener")
class ProductionEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventListener.class);

    private final ProductionRecords production;
    private final OrderReleaseService releases;
    private final Clock clock;

    ProductionEventListener(ProductionRecords production, OrderReleaseService releases, Clock clock) {
        this.production = production;
        this.releases = releases;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(SampleApproved event) {
        production.recordSampleApproved(event.sampleRequestId(), event.customerId(), event.designSnapshotId(),
                event.blankSku(), clock.instant());
        log.info("Sample {} approved: design {} on {} may go to print", event.sampleRequestId(),
                event.designSnapshotId(), event.blankSku());
    }

    /** BR-PRD-06: the order moves on only when every print line is covered. */
    @ApplicationModuleListener
    public void on(ProductionCompleted event) {
        if (!production.recordCompleted(event.productionOrderId(), event.orderLineId(), event.goodQuantity(),
                clock.instant())) {
            log.debug("Production order {} already recorded", event.productionOrderId());
            return;
        }
        releases.productionFinished(event.orderId());
    }
}
