package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderReservations;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** {@link OrderReservations} on the order's row lock. */
@Service
@Transactional
class OrderReservationService implements OrderReservations {

    private static final Logger log = LoggerFactory.getLogger(OrderReservationService.class);

    private final OrderRepository orders;
    private final com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords;

    OrderReservationService(OrderRepository orders,
                            com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords) {
        this.orders = orders;
        this.batchRecords = batchRecords;
    }

    @Override
    public void replaceHold(UUID orderId, UUID reservationId, Collection<UUID> replacements) {
        Order order = orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        if (order.replaceReservation(reservationId, List.copyOf(replacements))) {
            orders.save(order);
            // 4B-8: a reallocated hold still stands for its delivery batch.
            batchRecords.carry(reservationId, replacements);
            log.info("Order {}: hold {} reallocated to {} hold(s)", order.orderNumber(), reservationId,
                    replacements.size());
        }
    }

    @Override
    public void cartonRemoved(UUID orderId, UUID reservationId) {
        // No order lock: the row is the batch's record of the hold, and the batch was released already - nothing that
        // holds or releases for a batch (both under the order's lock) reads a released batch's rows to change them.
        if (batchRecords.forget(orderId, reservationId)) {
            log.info("Order {}: hold {} no longer stands for its delivery batch - its carton was removed",
                    orderId, reservationId);
        }
    }

    @Override
    public void holdReplacement(UUID orderId, UUID orderLineId, UUID deliveryBatchId,
                                Collection<UUID> reservationIds) {
        if (reservationIds == null || reservationIds.isEmpty()) {
            return;
        }
        Order order = orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        order.holdMore(orderLineId, List.copyOf(reservationIds));
        orders.save(order);
        if (deliveryBatchId != null) {
            batchRecords.recordHolds(orderId, deliveryBatchId, orderLineId, reservationIds);
        }
        log.info("Order {}: line {} holds {} replacement hold(s) for a carton removed at the check station",
                order.orderNumber(), orderLineId, reservationIds.size());
    }
}
