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
 * of that warehouse and it is READY_TO_FULFILL.
 *
 * <p>An order with a print line is not released yet ({@code ORDER_PRINT_GOODS_NOT_RECEIVED}). The goods are printed
 * by a producer outside the system (kltn-docs 07/11) and enter stock only when received against a Production Order,
 * which is not built yet; until then a print line names the blank its design goes on, and releasing would allocate,
 * pick and ship plain blanks. The order waits CONFIRMED. The rule goes when lines name the customer's printed SKU.</p>
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

    OrderReleaseService(OrderRepository repository, WarehouseDirectory warehouses,
                        OrderEventPublisher events, OrderService orders, Clock clock,
                        InventoryService inventory, com.stockflow.payment.api.PaymentService paymentService,
                        OrderStockAllocation allocation) {
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
            throw new BusinessException(ErrorCode.ORDER_PRINT_GOODS_NOT_RECEIVED, ("Order %s has print lines; their "
                    + "goods are received from the producer before it is released").formatted(order.orderNumber()));
        }

        // kltn-docs 15 §4.3 step 4, 17 BR-02: no credit order is released for a customer who is overdue - nor a deposit
        // order whose balance goes on credit (BALCREDIT, review of #150): it gives the same credit on delivery.
        if ((order.paymentTerm() == com.stockflow.order.api.PaymentTerm.CREDIT || order.balanceOnCredit())
                && paymentService.creditPosition(order.customerId()).hasOverdue()) {
            throw new BusinessException(ErrorCode.CUSTOMER_CREDIT_OVERDUE,
                    "Order %s is on credit and its customer has overdue receivables".formatted(order.orderNumber()));
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
}
