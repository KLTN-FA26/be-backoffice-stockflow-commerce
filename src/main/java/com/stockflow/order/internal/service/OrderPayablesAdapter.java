package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.api.PayableOrder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The order module's answer to the money ledger (G2-3): what an order is worth and whether money taken off it would
 * leave it short of its terms. Handed to payment by Spring; see {@link OrderPayables} for why payment does not call
 * the order module itself.
 *
 * <p>{@link #lockForPayment} joins payment's transaction and takes the order's row lock there, so an allocation, a
 * reversal, a cancellation and a handover of one order never interleave: each sees the others' result.</p>
 */
@Component
class OrderPayablesAdapter implements OrderPayables {

    private final OrderRepository orders;

    OrderPayablesAdapter(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PayableOrder> lockForPayment(UUID orderId) {
        return orders.findByIdForUpdate(new OrderId(orderId)).map(OrderPayablesAdapter::toPayable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PayableOrder> find(UUID orderId) {
        return orders.findById(new OrderId(orderId)).map(OrderPayablesAdapter::toPayable);
    }

    private static PayableOrder toPayable(Order order) {
        boolean holdable = order.status().mayBePutOnHold();
        return new PayableOrder(order.id().value(), order.orderNumber().value(), order.customerId(),
                order.status().name(), order.paymentTerm().name(),
                order.balancePolicy() == null ? null : order.balancePolicy().name(),
                order.total().currency().getCurrencyCode(), order.billable(), order.depositRequired(),
                order.codInTransit(), order.status() == OrderStatus.CANCELLED ? null : order.confirmationThreshold(),
                holdable);
    }
}
