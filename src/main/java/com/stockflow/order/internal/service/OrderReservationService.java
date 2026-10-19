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

    OrderReservationService(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public void addProductionHolds(UUID orderId, UUID orderLineId, Collection<UUID> reservationIds) {
        if (reservationIds == null || reservationIds.isEmpty()) {
            return;
        }
        Order order = orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        if (order.addProductionHolds(orderLineId, List.copyOf(reservationIds))) {
            orders.save(order);
            log.info("Order {} line {} holds {} more reservation(s) from production", order.orderNumber(),
                    orderLineId, reservationIds.size());
        }
    }
}
