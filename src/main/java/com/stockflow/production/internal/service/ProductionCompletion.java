package com.stockflow.production.internal.service;

import com.stockflow.contracts.ProductionCompleted;
import com.stockflow.order.api.OrderReservations;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The hand-over of a COMPLETED production order (SCRUM-400), in the caller's transaction, once: by the QC round
 * that completed it, or by a subcontracted order whose last owed units went back to the print shop (03 §16). The
 * two callers share it so the goods, the announcement and the subcontract settlement happen the same way whichever
 * step finished the order.
 */
@Component
class ProductionCompletion {

    private static final Logger log = LoggerFactory.getLogger(ProductionCompletion.class);

    private final WorkshopStock stock;
    private final SampleProductionProgress samples;
    private final OrderReservations orderReservations;
    private final SubcontractSettling settling;
    private final ApplicationEventPublisher events;
    private final ProductionOrderRepository orders;

    ProductionCompletion(WorkshopStock stock, SampleProductionProgress samples, OrderReservations orderReservations,
                         SubcontractSettling settling, ApplicationEventPublisher events,
                         ProductionOrderRepository orders) {
        this.orders = orders;
        this.stock = stock;
        this.samples = samples;
        this.orderReservations = orderReservations;
        this.settling = settling;
        this.events = events;
    }

    /**
     * Blanks issued and never printed go back to storage, free: the order has its good units. Then an ORDER's goods,
     * still held for it, go to packing and the order is told once; a SAMPLE's are written off as SAMPLE and its round
     * becomes READY. A subcontracted order's PO is closed for production and settled (BR-PRD-17).
     */
    void complete(ProductionOrder order, String packingLocation, UUID by) {
        complete(order, packingLocation, by, null, null);
    }

    /**
     * The same, with a fee agreed with the subcontractor for work it gave up (decision QA2), added to its settlement.
     */
    void complete(ProductionOrder order, String packingLocation, UUID by, java.math.BigDecimal subcontractorFee,
                  String feeReason) {
        if (order.status() != ProductionOrder.Status.COMPLETED) {
            throw new IllegalStateException("Production order %s is %s, not COMPLETED".formatted(order.orderNumber(),
                    order.status()));
        }
        settling.requireBlanksAccountedFor(order);
        if (order.mode() == ProductionOrder.Mode.IN_HOUSE && order.unprintedBlanks() > 0) {
            stock.returnUnprinted(order, order.unprintedBlanks(), false, by);
        }
        // Before the hand-over: a production hold that reached packing is finished goods, not a spare blank.
        stock.releaseUnissued(order);
        if (order.type() == ProductionOrder.Type.SAMPLE) {
            stock.writeOffSample(order, order.good(), by);
            samples.productionCompleted(order.id());
            return;
        }
        // Automatic, in this transaction: nobody confirms the hand-over a second time (decided 14/10/2026,
        // SCRUM-412). The area is recorded on the order so the completed list can say where the goods are.
        WorkshopStock.HandOver handOver = stock.finishToPacking(order, packingLocation, by);
        orders.recordHandOver(order.id(), handOver.packingLocation());
        // Every good unit is the order's to ship: reprint holds and a subcontractor's goods join the line's
        // checkout holds.
        orderReservations.addProductionHolds(order.salesOrderId(), order.salesOrderLineId(),
                stock.handFinishedGoodsToOrder(order, handOver.holds()));
        events.publishEvent(new ProductionCompleted(order.salesOrderId(), order.salesOrderLineId(), order.id(),
                order.good()));
        if (order.mode() == ProductionOrder.Mode.SUBCONTRACTED) {
            settling.settle(order, "Production order %s completed".formatted(order.orderNumber()), subcontractorFee,
                    feeReason, by);
        }
        log.info("Production order {} completed: {} good unit(s) to packing at {}", order.orderNumber(), order.good(),
                handOver.packingLocation());
    }
}
