package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * A cancelled sales order stops its production orders (4A-6; kltn-docs 17 §4.8 steps 30 and 32, §5.3, BR-06, BR-09):
 * those not mass printing yet (DRAFT, PENDING_APPROVAL, SENT, ACCEPTED, SAMPLE_PENDING) are cancelled; those printing
 * (IN_PRODUCTION) or partly received are closed - what the producer already made and still delivers is received as the
 * customer's surplus (BR-07). Each with the order's cancellation as its reason and who cancelled the order as its actor.
 * The rest of a sales order closed stops them the same way (17 §4.8 step 33, WF4-CLOSE-REST 2026-11-13).
 * A production order already sent tells its producer to stop through {@code ProductionOrderService.cancel} and
 * {@code close} (4A-3).
 *
 * <p>After the order's commit, like every reaction to {@code OrderCancelled}. One transaction per production order,
 * each locking the sales order first and then the production order - the lock order of every write to a production
 * order - so one refused does not undo the others. A production order that refuses because goods are being received
 * against it (a draft goods receipt, {@code PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS}, 4A-5) is passed by here: it is
 * stopped when its last draft is confirmed or cancelled ({@code ProductionOrderReceivingService}), in that receipt's
 * transaction, so it is not left open whatever the event registry does next, and its producer is told there too.
 * Any other failure is raised once the rest were tried, so the event stays incomplete and is delivered again; a redelivery skips what was
 * stopped already - a production order received, cancelled or closed is left as it is.</p>
 *
 * <p>{@code @Async @TransactionalEventListener}, not {@code @ApplicationModuleListener}: the latter wraps the whole
 * method in a REQUIRES_NEW transaction, and its connection would sit idle while each production order borrows a
 * second one. Many orders cancelled together (a contract's) would then hold every pooled connection waiting for
 * another. Without the outer transaction each thread borrows one connection at a time; the event publication registry
 * still records the delivery and leaves it incomplete when this method throws.</p>
 */
@Component
class SalesOrderCancellationListener {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderCancellationListener.class);

    /** 4A-5's refusal while a goods receipt against the production order is a draft; by name, as it is 4A-5's code. */
    static final String DRAFT_RECEIPTS = "PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS";

    private final ProductionOrderService service;
    private final ProductionOrderRepository productionOrders;
    private final OrderRepository orders;
    private final TransactionTemplate eachProductionOrder;

    SalesOrderCancellationListener(ProductionOrderService service, ProductionOrderRepository productionOrders,
                                   OrderRepository orders, PlatformTransactionManager transactions) {
        this.service = service;
        this.productionOrders = productionOrders;
        this.orders = orders;
        this.eachProductionOrder = new TransactionTemplate(transactions);
        this.eachProductionOrder.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async
    @TransactionalEventListener
    public void on(OrderCancelled event) {
        var open = productionOrders.ofSalesOrder(event.orderId()).stream()
                .filter(production -> production.status().open()).toList();
        if (open.isEmpty()) {
            return;
        }
        if (event.cancelledBy() == null) {
            // An order with production orders has a signed contract, cancelled by someone (OrderServiceImpl); were one
            // cancelled by the system, nobody could be named on the production orders.
            log.warn("Order {} was cancelled by no one: its {} open production order(s) are left to the coordinator",
                    event.orderNumber(), open.size());
            return;
        }
        stopAll(open, event.orderId(), event.orderNumber(), event.cancelledBy(), reasonOf(event), "was cancelled");
    }

    /**
     * The rest of the sales order was closed (kltn-docs 17 §4.8 step 33; owner decision WF4-CLOSE-REST, 2026-11-13):
     * whatever it still wants is labelled at dispatch already, so its production orders are stopped as a cancellation
     * stops them - not mass printing yet: cancelled; printing or partly received: closed. Each with the closure as its
     * reason and who closed the rest as its actor; the same transactions and redelivery as {@link #on(OrderCancelled)}.
     */
    @Async
    @TransactionalEventListener
    public void on(com.stockflow.contracts.OrderRemainderClosed event) {
        var open = productionOrders.ofSalesOrder(event.orderId()).stream()
                .filter(production -> production.status().open()).toList();
        if (open.isEmpty()) {
            return;
        }
        if (event.closedBy() == null) {
            log.warn("The rest of order {} was closed by no one: its {} open production order(s) are left to the "
                    + "coordinator", event.orderNumber(), open.size());
            return;
        }
        stopAll(open, event.orderId(), event.orderNumber(), event.closedBy(), reasonOf(event), "had its rest closed");
    }

    private void stopAll(java.util.List<ProductionOrder> open, UUID orderId, String orderNumber, UUID by, String reason,
                         String what) {
        RuntimeException failure = null;
        for (ProductionOrder production : open) {
            try {
                eachProductionOrder.executeWithoutResult(status -> stop(production.id(), orderId, orderNumber, by,
                        reason));
            } catch (BusinessException e) {
                if (DRAFT_RECEIPTS.equals(e.errorCode().name())) {
                    log.info("Order {} {}; production order {} has a goods receipt in progress and is stopped once it "
                            + "is settled", orderNumber, what, production.code());
                } else {
                    failure = keep(failure, e);
                }
            } catch (RuntimeException e) {
                failure = keep(failure, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** One production order, in its own transaction: the sales order locked first, then it. */
    private void stop(UUID productionOrderId, UUID orderId, String orderNumber, UUID by, String reason) {
        orders.findByIdForUpdate(new OrderId(orderId)).orElseThrow();
        ProductionOrder production = productionOrders.findByIdForUpdate(productionOrderId).orElseThrow();
        if (production.status().cancellable()) {
            service.cancel(productionOrderId, production.version(), reason, by);
            log.info("Production order {} cancelled with its sales order {}", production.code(), orderNumber);
        } else if (production.status().canTransitionTo(ProductionOrderStatus.CLOSED)) {
            service.close(productionOrderId, production.version(), reason, by);
            log.info("Production order {} closed with its sales order {}: mass printing had started",
                    production.code(), orderNumber);
        }
    }

    /** "Sales order SO-… was cancelled: CODE - note", within what a production order keeps of a reason. */
    static String reasonOf(OrderCancelled event) {
        return fitted("Sales order %s was cancelled: %s".formatted(event.orderNumber(), event.reasonCode())
                + (event.note() == null || event.note().isBlank() ? "" : " - " + event.note().strip()));
    }

    /** "Sales order SO-… had its rest closed: CODE", within what a production order keeps of a reason. */
    static String reasonOf(com.stockflow.contracts.OrderRemainderClosed event) {
        return fitted("Sales order %s had its rest closed: %s".formatted(event.orderNumber(), event.reasonCode()));
    }

    private static String fitted(String reason) {
        if (reason.length() <= ProductionOrder.TEXT_MAX) {
            return reason;
        }
        int end = ProductionOrder.TEXT_MAX;
        if (Character.isHighSurrogate(reason.charAt(end - 1))) {
            end--;
        }
        return reason.substring(0, end);
    }

    private static RuntimeException keep(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
