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
}
