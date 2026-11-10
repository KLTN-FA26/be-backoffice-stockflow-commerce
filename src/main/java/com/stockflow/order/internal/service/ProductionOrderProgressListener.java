package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The sales order follows its production orders (4A-6; kltn-docs 17 §4.2 step 5, §4.3 steps 7 and 10, §5.1, BR-04,
 * BR-11), whatever moved them - approval today, sample rounds, progress and receiving later - since the repository
 * publishes every saved change of status ({@link ProductionOrderStatusChanged}):
 * <ul>
 *   <li><b>SENT</b>: an order CONFIRMED whose next delivery batch is short at its shipping warehouse, with a production
 *       order for that batch now sent, waits for stock - AWAITING_STOCK (BR-04: such an order always has one). A batch
 *       held in full does not make it wait: 17 §4.2 step 5 decides "đủ hàng / thiếu hàng" on the next batch only, so a
 *       production order for a later batch leaves it CONFIRMED.</li>
 *   <li><b>IN_PRODUCTION</b>: the first production order of the order to start mass printing sets
 *       {@code printing_started_at} (BR-11) - once; from then a cancellation is staff's and may keep an agreed fee.</li>
 * </ul>
 *
 * <p><b>Synchronous, in the caller's transaction.</b> The sales order moves with the production order or not at all: an
 * after-commit listener could fail once the production order had committed, leaving an order CONFIRMED with a
 * production order sent (BR-04 broken) or one printing with no {@code printing_started_at} - which would refund a
 * cancellation in full that BR-11 lets staff charge for - until a redelivery that comes only on a restart. It is safe
 * because of the lock order every write to a production order keeps: the sales order row first, then the production
 * order ({@code ProductionOrderService.lockedWithOrder}). The caller holds the sales order already, so loading it here
 * {@code FOR UPDATE} takes no new lock and waits for no one - and a cancellation, a release or a top-up, which lock the
 * sales order first too, cannot interleave. A path that saved a production order without locking its sales order
 * first would take that lock here, after the production order's: such a path is the defect, not this listener.</p>
 *
 * <p>A caller that holds a copy of the sales order must not save it after saving the production order: the order's
 * save writes the whole aggregate and would put back the status changed here. Save the production order last, or
 * reload the order.</p>
 */
@Component
class ProductionOrderProgressListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderProgressListener.class);

    private final OrderRepository orders;
    private final ProductionOrderRepository productionOrders;
    private final ShippingWarehouseService shippingWarehouses;

    ProductionOrderProgressListener(OrderRepository orders, ProductionOrderRepository productionOrders,
                                    ShippingWarehouseService shippingWarehouses) {
        this.orders = orders;
        this.productionOrders = productionOrders;
        this.shippingWarehouses = shippingWarehouses;
    }

    /** MANDATORY: the change and the order's move are one transaction - there is no order to move outside one. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(ProductionOrderStatusChanged change) {
        switch (change.to()) {
            case SENT -> awaitStock(change);
            case IN_PRODUCTION -> printingStarted(change);
            default -> {
                // DRAFT, PENDING_APPROVAL, ACCEPTED, SAMPLE_PENDING, received, cancelled, closed: nothing on the order.
            }
        }
    }

    private void awaitStock(ProductionOrderStatusChanged change) {
        Order order = salesOrder(change);
        boolean confirmed = order.status() == OrderStatus.CONFIRMED
                || order.status() == OrderStatus.ON_HOLD && order.statusBeforeHold() == OrderStatus.CONFIRMED;
        if (!confirmed || !order.paidByContract()) {
            return;
        }
        Optional<ShippingWarehouseService.NextBatch> next = shippingWarehouses.nextBatch(order);
        if (next.isEmpty() || next.get().fullyHeld()) {
            return;
        }
        boolean sentForIt = productionOrders.ofSalesOrder(order.id().value()).stream()
                .anyMatch(production -> production.sentAndOpen()
                        && production.deliveryBatchId().equals(next.get().batchId()));
        if (sentForIt && order.awaitStock(change.by(), change.at())) {
            orders.save(order);
            log.info("Order {} waits for stock: production order {} sent for its delivery batch {}",
                    order.orderNumber(), change.code(), next.get().seq());
        }
    }

    private void printingStarted(ProductionOrderStatusChanged change) {
        Order order = salesOrder(change);
        if (order.recordPrintingStarted(change.at())) {
            orders.save(order);
            log.info("Order {}: printing started with production order {} (17 BR-11)", order.orderNumber(),
                    change.code());
        } else if (order.printingStartedAt() == null) {
            // Cancelled or closed before it: the cancellation's cascade closes the production order.
            log.warn("Order {} is {}: production order {} started printing for it; printing start not recorded",
                    order.orderNumber(), order.status(), change.code());
        }
    }

    /** The production order's sales order, by the lock its caller already holds (class comment). */
    private Order salesOrder(ProductionOrderStatusChanged change) {
        return orders.findByIdForUpdate(new OrderId(change.salesOrderId())).orElseThrow(() ->
                new IllegalStateException("Production order %s has no sales order %s"
                        .formatted(change.code(), change.salesOrderId())));
    }
}
