package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Releasing an order to a warehouse (SCRUM-423, kltn-docs 17 §4.2): its SKU reservations become allocations on bins
 * of that warehouse and it is READY_TO_FULFILL - from CONFIRMED, or from AWAITING_STOCK once every carton is held at
 * the order's shipping warehouse (4A-6). Since 4B-8 an order with a signed delivery schedule is released one batch at a
 * time (17 §4.5, BR-29): the next batch, held in full, once the one before it was handed over - only that batch's holds
 * are allocated, and a later batch leaves the order PARTIALLY_FULFILLED.
 *
 * <p>A line of the customer's own SKU is stock like any other (team plan QD-16): its goods arrive printed from the
 * producer, are received against a production order and put away, and are held for the order like any stock - so it
 * is released as soon as every carton is held. Only a legacy print line - a blank with a design snapshot, placed before
 * lines named the customer's SKU (4B-2) - is still refused ({@code ORDER_PRINT_GOODS_NOT_RECEIVED}): it names the
 * blank its design goes on, whose printed goods were never received into stock, and releasing it would allocate, pick
 * and ship plain blanks. Such a line is kept as history, read only (QD-16).</p>
 */
@Service
@Transactional
class OrderReleaseService implements OrderReleases {

    private static final Logger log = LoggerFactory.getLogger(OrderReleaseService.class);

    private final OrderRepository repository;
    private final WarehouseDirectory warehouses;
    private final OrderEventPublisher events;
    private final OrderService orders;
    private final Clock clock;
    private final com.stockflow.payment.api.PaymentService paymentService;
    private final OrderStockAllocation allocation;
    private final com.stockflow.order.internal.domain.SalesContractRepository contracts;
    private final ShippingWarehouseService shippingWarehouses;
    private final DeliveryBatchService batches;
    private final com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords;
    private final com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures;

    OrderReleaseService(OrderRepository repository, WarehouseDirectory warehouses,
                        OrderEventPublisher events, OrderService orders, Clock clock,
                        com.stockflow.payment.api.PaymentService paymentService,
                        OrderStockAllocation allocation, com.stockflow.order.internal.domain.SalesContractRepository contracts,
                        ShippingWarehouseService shippingWarehouses, DeliveryBatchService batches,
                        com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords,
                        com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures) {
        this.deliveryFailures = deliveryFailures;
        this.batches = batches;
        this.batchRecords = batchRecords;
        this.shippingWarehouses = shippingWarehouses;
        this.contracts = contracts;
        this.allocation = allocation;
        this.repository = repository;
        this.warehouses = warehouses;
        this.events = events;
        this.orders = orders;
        this.clock = clock;
        this.paymentService = paymentService;
    }

    /** Declared here, not as an interface default: the class's {@code @Transactional} must cover it (a default
     *  method of the interface would run on the proxy without a transaction). */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId")
    public OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy) {
        return release(orderId, warehouseId, null, releasedBy);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId")
    public OrderSummary release(UUID orderId, UUID warehouseId, UUID deliveryBatchId, UUID releasedBy) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        String warehouseCode = warehouses.codeOf(warehouseId).orElseThrow(() ->
                new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId));
        // WF-05 fix plan, section 2: goods of a shipment that came back, which Sales decided to deliver again, are
        // released before anything else of the order - the batch after it waits for them to be handed over.
        java.util.Optional<com.stockflow.order.internal.domain.DeliveryFailureCase> redelivery = deliveryFailures
                .ofOrder(orderId).stream()
                .filter(com.stockflow.order.internal.domain.DeliveryFailureCase::awaitingRelease).findFirst();
        if (redelivery.isPresent()) {
            return releaseRedelivery(order, redelivery.get(), warehouseId, warehouseCode, deliveryBatchId, releasedBy);
        }
        if (order.hasPrintLines()) {
            // QD-16: a legacy line of a blank and a design snapshot only; the customer's own SKU is stock.
            throw new BusinessException(ErrorCode.ORDER_PRINT_GOODS_NOT_RECEIVED, ("Order %s has print lines; their "
                    + "goods are received from the producer before it is released").formatted(order.orderNumber()));
        }

        // kltn-docs 15 §4.3 step 4, 17 BR-02: an order whose contract pays after delivery gives credit on delivery, so it
        // is not released for a customer who is overdue (4B-5; until 4B-10 also checkout's credit orders).
        if (paysAfterDelivery(order) && paymentService.creditPosition(order.customerId()).hasOverdue()) {
            throw new BusinessException(ErrorCode.CUSTOMER_CREDIT_OVERDUE,
                    "Order %s is on credit and its customer has an overdue installment".formatted(order.orderNumber()));
        }
        // 4B-8 (17 §4.5, BR-03, BR-29): an order with a signed delivery schedule is released batch by batch - the next
        // one, held in full at the shipping warehouse, once the batch before it was handed over.
        java.util.Optional<com.stockflow.order.internal.domain.DeliveryBatch> next = order.paidByContract()
                && batches.scheduled(order) ? batches.nextToRelease(order) : java.util.Optional.empty();
        if (order.paidByContract() && batches.scheduled(order)) {
            com.stockflow.order.internal.domain.DeliveryBatch batch = next.orElseThrow(() -> new BusinessException(
                    ErrorCode.DELIVERY_BATCH_NOT_NEXT, "Every delivery batch of order %s is released already"
                            .formatted(order.orderNumber())));
            if (deliveryBatchId != null && !deliveryBatchId.equals(batch.id())) {
                throw new BusinessException(ErrorCode.DELIVERY_BATCH_NOT_NEXT, ("Order %s releases delivery batch %d "
                        + "next").formatted(order.orderNumber(), batch.seq()));
            }
            return releaseBatch(order, batch, warehouseId, warehouseCode, releasedBy);
        }
        if (order.paidByContract()) {
            // 4B-7 (17 §4.2 step 5): an order of a contract without a schedule is released once every carton of every
            // line is held at its shipping warehouse. Order#release checks the warehouse itself.
            shippingWarehouses.requireFullyHeld(order);
        }
        order.release(warehouseId, warehouseCode, releasedBy, clock.instant());
        // F-FUL step 02 (SCRUM-152): the SKU reservations taken at checkout become allocations on pickable bins of
        // this warehouse, FEFO - every line. All or nothing: a warehouse that cannot cover a line refuses the release,
        // and the order stays as it was.
        allocation.allocate(order, warehouseCode);
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Released order {} from {}: {}", order.orderNumber(), warehouseCode, order.status());
        return orders.findById(orderId).orElseThrow();
    }

    /**
     * One delivery batch to the warehouse: its own holds only are allocated, the release is recorded, and payment makes
     * due what the batch waits for ({@code OrderReleased} names it). The first batch moves the order to
     * READY_TO_FULFILL; a later one leaves it PARTIALLY_FULFILLED (17 §4.5: no new order status).
     */
    private OrderSummary releaseBatch(Order order, com.stockflow.order.internal.domain.DeliveryBatch batch,
                                      UUID warehouseId, String warehouseCode, UUID releasedBy) {
        shippingWarehouses.requireBatchHeld(order, batch);
        java.time.Instant now = clock.instant();
        if (batch.seq() == 1 || order.releasedAt() == null) {
            order.release(warehouseId, warehouseCode, releasedBy, now, batch.id(), DeliveryBatchService.linesOf(batch));
        } else {
            order.releaseLaterBatch(warehouseId, warehouseCode, batch.id(), now);
        }
        allocation.allocate(order, warehouseCode, shippingWarehouses.heldFor(order, batch));
        batchRecords.recordRelease(order.id().value(), new com.stockflow.order.internal.domain.DeliveryBatchRecords
                .Release(batch.id(), batch.seq(), warehouseId, now, releasedBy));
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Released delivery batch {} of order {} from {}: {}", batch.seq(), order.orderNumber(), warehouseCode,
                order.status());
        return orders.findById(order.id().value()).orElseThrow();
    }

    /**
     * The goods of a returned shipment to the warehouse again (WF-05 fix plan, section 2; 09 §4.3): every unit of the
     * case held there - what just became available is held first - its holds allocated on bins, the case released. The
     * order stays PARTIALLY_FULFILLED (17 §4.5: no new status); fulfillment opens a pick task for the redelivery, the
     * cartons are checked and labelled again, and the handover closes the case. No credit gate: these goods were
     * released once already, and nothing new is given on credit by delivering them.
     *
     * @throws BusinessException {@code CONFLICT} for another warehouse than the goods came back to;
     *         {@code DELIVERY_BATCH_NOT_NEXT} when another batch is named; {@code ORDER_INVALID_TRANSITION} while the
     *         order is on hold; {@code ORDER_STOCK_NOT_HELD} while goods are in quarantine or written off
     */
    private OrderSummary releaseRedelivery(Order order, com.stockflow.order.internal.domain.DeliveryFailureCase failure,
                                           UUID warehouseId, String warehouseCode, UUID deliveryBatchId,
                                           UUID releasedBy) {
        if (!failure.warehouseId().equals(warehouseId)) {
            throw new BusinessException(ErrorCode.CONFLICT, ("The goods of return receipt %s came back to another "
                    + "warehouse; order %s delivers them again from there").formatted(failure.receiptNumber(),
                    order.orderNumber()));
        }
        if (deliveryBatchId != null && !deliveryBatchId.equals(failure.deliveryBatchId())) {
            throw new BusinessException(ErrorCode.DELIVERY_BATCH_NOT_NEXT, ("Order %s releases the goods of return "
                    + "receipt %s next, to be delivered again").formatted(order.orderNumber(), failure.receiptNumber()));
        }
        if (order.status() != com.stockflow.order.api.OrderStatus.PARTIALLY_FULFILLED) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_TRANSITION, ("Order %s is %s: goods that came back are "
                    + "released to be delivered again once every delivery failure of the order is decided")
                    .formatted(order.orderNumber(), order.status()));
        }
        shippingWarehouses.holdReturnedGoods(order);
        allocation.allocate(order, warehouseCode, shippingWarehouses.heldForRedelivery(order, failure));
        failure.release(releasedBy, clock.instant());
        deliveryFailures.save(failure);
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Released the goods of return receipt {} of order {} from {} to be delivered again",
                failure.receiptNumber(), order.orderNumber(), warehouseCode);
        return orders.findById(order.id().value()).orElseThrow();
    }

    /** Whether the order's signed contract pays after delivery (15 BR-09): credit given on delivery. */
    private boolean paysAfterDelivery(Order order) {
        return order.paidByContract() && contracts.findLiveByOrderId(order.id().value()).stream()
                .flatMap(contract -> contract.plan().stream())
                .anyMatch(i -> i.milestone() == com.stockflow.order.internal.domain.PaymentMilestone.AFTER_DELIVERY);
    }
}
