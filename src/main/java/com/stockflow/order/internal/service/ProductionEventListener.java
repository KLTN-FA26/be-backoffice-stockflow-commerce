package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.contracts.ProductionCompleted;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.internal.domain.ProductionRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Keeps the order module's own record of what production did (SCRUM-423). Asynchronous and after
 * commit, like the payment listener: production's fact stands whatever the order does with it, and a
 * failure here is retried from the event publication registry. Production's events are recorded
 * idempotently, keyed by the producer's id, and a shortfall's closure only looks at the order again,
 * so a redelivered event changes nothing.
 */
@Component("orderProductionEventListener")
class ProductionEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionEventListener.class);

    private final ProductionRecords production;
    private final OrderReleaseService releases;
    private final Clock clock;
    private final InventoryService inventory;

    ProductionEventListener(ProductionRecords production, OrderReleaseService releases, Clock clock,
                            InventoryService inventory) {
        this.production = production;
        this.releases = releases;
        this.clock = clock;
        this.inventory = inventory;
    }

    /**
     * The blank is the inventory item of the SKU (kltn-docs glossary "Phôi / Blank"; fk_approved_sample_blank).
     * A blank with no item is refused by name here rather than as a bare foreign-key violation; the event stays
     * incomplete in the publication registry, to be looked at.
     */
    @ApplicationModuleListener
    public void on(SampleApproved event) {
        if (inventory.itemPolicy(new Sku(event.blankSku())).isEmpty()) {
            throw new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND,
                    "Sample %s names blank %s, which has no inventory item"
                            .formatted(event.sampleRequestId(), event.blankSku()));
        }
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

    /**
     * BR-PRD-06 after a production shortfall (review 2026-10-25 of S6): a job closed with nothing made announces no
     * completion, yet the units it gave up may leave every print line covered by what its siblings made - a
     * subcontract's shortfall job the blanks never came for. Looked at again here; a no-op while a line is still short,
     * and for a job completed short its own completion moves the order instead.
     */
    @ApplicationModuleListener
    public void on(OrderRemainderClosed event) {
        if (OrderRemainderClosed.PRODUCTION_SHORTFALL.equals(event.reasonCode())) {
            releases.productionFinished(event.orderId());
        }
    }
}
