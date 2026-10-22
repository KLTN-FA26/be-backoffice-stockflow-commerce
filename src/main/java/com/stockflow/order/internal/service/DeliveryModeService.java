package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.api.DeliveryModeChange;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderDeliveryModeChangeJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * See {@link DeliveryModes}. The order's row lock first, then its history row, in one transaction - the same lock
 * fulfillment takes when it opens the order's first package, so a change and the start of packing never cross.
 */
@Service
@Transactional
class DeliveryModeService implements DeliveryModes {

    private final OrderRepository repository;
    private final OrderDeliveryModeChangeJpaRepository changes;
    private final DeliveryModeHistory history;
    private final OrderService orders;

    DeliveryModeService(OrderRepository repository, OrderDeliveryModeChangeJpaRepository changes,
                        DeliveryModeHistory history, OrderService orders) {
        this.repository = repository;
        this.changes = changes;
        this.history = history;
        this.orders = orders;
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "order-delivery-mode", resourceId = "#orderId")
    public OrderSummary change(UUID orderId, DeliveryMode mode, String note, UUID by) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        if (history.apply(order, mode, note, by)) {
            repository.save(order);
        }
        return orders.findById(orderId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryModeChange> changes(UUID orderId) {
        return changes.findByOrderIdOrderByChangedAtAscIdAsc(orderId).stream()
                .map(row -> new DeliveryModeChange(DeliveryMode.valueOf(row.getDeliveryMode()),
                        DeliveryMode.valueOf(row.getPreviousMode()), OrderStatus.valueOf(row.getOrderStatus()),
                        row.getNote(), row.getChangedBy(), row.getChangedAt()))
                .toList();
    }
}
