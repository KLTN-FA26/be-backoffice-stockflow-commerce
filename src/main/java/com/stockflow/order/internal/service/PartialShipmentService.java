package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PartialShipmentDecision;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.entity.OrderPartialShipmentDecisionJpaEntity;
import com.stockflow.order.internal.repository.OrderPartialShipmentDecisionJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** See {@link PartialShipments}. The order's row lock first, then its decision row, in one transaction. */
@Service
@Transactional
class PartialShipmentService implements PartialShipments {

    private static final Logger log = LoggerFactory.getLogger(PartialShipmentService.class);

    private final OrderRepository repository;
    private final OrderPartialShipmentDecisionJpaRepository decisions;
    private final OrderService orders;
    private final Clock clock;

    PartialShipmentService(OrderRepository repository, OrderPartialShipmentDecisionJpaRepository decisions,
                           OrderService orders, Clock clock) {
        this.repository = repository;
        this.decisions = decisions;
        this.orders = orders;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "order-partial-shipment", resourceId = "#orderId")
    public OrderSummary decide(UUID orderId, boolean allowed, String note, UUID by) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        if (order.decidePartialShipment(allowed)) {
            repository.save(order);
            decisions.save(new OrderPartialShipmentDecisionJpaEntity(Identifiers.newId(), orderId, allowed,
                    order.status().name(), note, by, clock.instant()));
            log.info("Order {}: partial shipments {} by {}", order.orderNumber(), allowed ? "allowed" : "stopped", by);
        }
        return orders.findById(orderId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartialShipmentDecision> decisions(UUID orderId) {
        return decisions.findByOrderIdOrderByDecidedAtAscIdAsc(orderId).stream()
                .map(row -> new PartialShipmentDecision(row.isAllowed(), OrderStatus.valueOf(row.getOrderStatus()),
                        row.getNote(), row.getDecidedBy(), row.getDecidedAt()))
                .toList();
    }
}
