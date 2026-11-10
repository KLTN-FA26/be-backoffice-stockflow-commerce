package com.stockflow.order.internal.service;

import com.stockflow.inventory.api.InventoryService;
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
 * the order's shipping warehouse (4A-6).
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
    private final InventoryService inventory;
    private final com.stockflow.payment.api.PaymentService paymentService;
    private final OrderStockAllocation allocation;
    private final com.stockflow.order.internal.domain.SalesContractRepository contracts;
    private final ShippingWarehouseService shippingWarehouses;

    OrderReleaseService(OrderRepository repository, WarehouseDirectory warehouses,
                        OrderEventPublisher events, OrderService orders, Clock clock,
                        InventoryService inventory, com.stockflow.payment.api.PaymentService paymentService,
                        OrderStockAllocation allocation, com.stockflow.order.internal.domain.SalesContractRepository contracts,
                        ShippingWarehouseService shippingWarehouses) {
        this.shippingWarehouses = shippingWarehouses;
        this.contracts = contracts;
        this.allocation = allocation;
        this.repository = repository;
        this.warehouses = warehouses;
        this.events = events;
        this.orders = orders;
        this.clock = clock;
        this.inventory = inventory;
        this.paymentService = paymentService;
    }

    /** Declared here, not as an interface default: the class's {@code @Transactional} must cover it (a default
     *  method of the interface would run on the proxy without a transaction). */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId")
    public OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        String warehouseCode = warehouses.codeOf(warehouseId).orElseThrow(() ->
                new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId));
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
        if (order.paidByContract()) {
            // 4B-7 (17 §4.2 step 5): released when its stock is all held at its shipping warehouse - every carton of
            // every line (until 4B-8 releases batch by batch), CONFIRMED or AWAITING_STOCK alike (4A-6). Order#release
            // checks the warehouse itself.
            shippingWarehouses.requireFullyHeld(order);
        }
        order.release(warehouseId, warehouseCode, releasedBy, clock.instant());
        // F-FUL step 02 (SCRUM-152): the SKU reservations taken at checkout become allocations on pickable bins of
        // this warehouse, FEFO - every line. All or nothing: a warehouse that cannot cover a line refuses the release,
        // and the order stays as it was.
        allocation.allocate(order, warehouseCode);
        // From release on the order's holds must not expire (kltn-docs 14 BR-07, SCRUM-465). Idempotent for an
        // order already paid and pinned.
        inventory.pinReservations(orderId);
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Released order {} from {}: {}", order.orderNumber(), warehouseCode, order.status());
        return orders.findById(orderId).orElseThrow();
    }

    /** Whether the order's signed contract pays after delivery (15 BR-09): credit given on delivery. */
    private boolean paysAfterDelivery(Order order) {
        return order.paidByContract() && contracts.findLiveByOrderId(order.id().value()).stream()
                .flatMap(contract -> contract.plan().stream())
                .anyMatch(i -> i.milestone() == com.stockflow.order.internal.domain.PaymentMilestone.AFTER_DELIVERY);
    }
}
