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
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ProductionRecords;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Releasing an order, and moving it on when production has delivered (SCRUM-423, docs 17 / 19 §4.1).
 *
 * <p>Release is where an ORDER production order is born: the event carries every print line, so the
 * production orders of one customer order are created together. The two rules release checks before
 * anything is printed — an approved sample for a new design (BR-PRD-08), the deposit (BR-PRD-09, in
 * the aggregate) — are the ones that cost money when they are missed: a print run nobody approved.</p>
 */
@Service
@Transactional
class OrderReleaseService implements OrderReleases {

    private static final Logger log = LoggerFactory.getLogger(OrderReleaseService.class);

    private final OrderRepository repository;
    private final ProductionRecords production;
    private final WarehouseDirectory warehouses;
    private final OrderEventPublisher events;
    private final OrderService orders;
    private final Clock clock;
    private final InventoryService inventory;
    private final com.stockflow.payment.api.PaymentService paymentService;

    OrderReleaseService(OrderRepository repository, ProductionRecords production, WarehouseDirectory warehouses,
                        OrderEventPublisher events, OrderService orders, Clock clock,
                        InventoryService inventory, com.stockflow.payment.api.PaymentService paymentService) {
        this.repository = repository;
        this.production = production;
        this.warehouses = warehouses;
        this.events = events;
        this.orders = orders;
        this.clock = clock;
        this.inventory = inventory;
        this.paymentService = paymentService;
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId")
    public OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        String warehouseCode = warehouses.codeOf(warehouseId).orElseThrow(() ->
                new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId));

        // BR-PRD-08: each new design needs a sample the customer approved; a design already printed to
        // completion for an earlier order is a repeat and needs none.
        Map<UUID, UUID> approvedSamples = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (OrderLine line : order.printLines()) {
            Optional<UUID> sample = production.approvedSample(line.designSnapshotId(), line.sku().code());
            if (sample.isPresent()) {
                approvedSamples.put(line.id(), sample.get());
            } else if (!production.producedBefore(line.designSnapshotId(), orderId)) {
                missing.add(line.sku().code());
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED,
                    "Order %s has new designs without an approved sample on %s (BR-PRD-08)"
                            .formatted(order.orderNumber(), String.join(", ", missing)));
        }

        // kltn-docs 15 §4.3 step 4, 17 BR-02: no credit order is released for a customer who is overdue.
        if (order.paymentTerm() == com.stockflow.order.api.PaymentTerm.CREDIT
                && paymentService.creditPosition(order.customerId()).hasOverdue()) {
            throw new BusinessException(ErrorCode.CUSTOMER_CREDIT_OVERDUE,
                    "Order %s is on credit and its customer has overdue receivables".formatted(order.orderNumber()));
        }
        order.release(warehouseId, warehouseCode, releasedBy, clock.instant(), approvedSamples);
        // A deposit order leaves PENDING_PAYMENT here (BR-PRD-09): from now on its holds must not
        // expire (kltn-docs 14 BR-07, SCRUM-465). Idempotent for an order already paid and pinned.
        inventory.pinReservations(orderId);
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Released order {} from {}: {} ({} print line(s))", order.orderNumber(), warehouseCode,
                order.status(), order.printLines().size());
        return orders.findById(orderId).orElseThrow();
    }

    /** Called by the production listener once a production order of a line is recorded as finished. */
    public void productionFinished(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || order.warehouseId() == null) {
            log.warn("Production finished for order {}, which is unknown or was never released", orderId);
            return;
        }
        String warehouseCode = warehouses.codeOf(order.warehouseId()).orElse(null);
        Instant now = clock.instant();
        if (order.completeProduction(production.producedByLine(orderId), warehouseCode, now)) {
            repository.save(order);
            events.publishEventsOf(order);
            log.info("Order {} is ready to fulfil: every print line produced", order.orderNumber());
        }
    }
}
