package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.OrderShipment;
import com.stockflow.payment.api.HandedOverGoods;
import com.stockflow.payment.api.InstallmentOrder;
import com.stockflow.payment.api.InstallmentOrders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The order module's answer to payment about the order of an installment (4B-10): whose it is, where it stands, when it
 * was delivered in full. Handed to payment by Spring; see {@link InstallmentOrders} for why payment does not call the
 * order module itself.
 *
 * <p>{@link #lockForPayment} joins payment's transaction and takes the order's row lock there, so a receipt, a void, a
 * refund record, a cancellation and a handover of one order never interleave: each sees the others' result.</p>
 */
@Component
class InstallmentOrdersAdapter implements InstallmentOrders {

    /** The statuses an order reaches only once all its goods were delivered (or the rest closed after the last). */
    private static final java.util.Set<OrderStatus> DELIVERED_IN_FULL = java.util.EnumSet.of(OrderStatus.DELIVERED,
            OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED, OrderStatus.REFUNDED, OrderStatus.PARTIALLY_REFUNDED,
            OrderStatus.COMPLETED, OrderStatus.CLOSED);

    private final OrderRepository orders;
    private final DeliveryBatchService batches;

    InstallmentOrdersAdapter(OrderRepository orders, DeliveryBatchService batches) {
        this.orders = orders;
        this.batches = batches;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, java.time.Instant> batchesDeliveredAt(UUID orderId) {
        return orders.findById(new OrderId(orderId)).map(batches::deliveredBatches).orElse(java.util.Map.of());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<InstallmentOrder> lockForPayment(UUID orderId) {
        return orders.findByIdForUpdate(new OrderId(orderId)).map(InstallmentOrdersAdapter::toInstallmentOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InstallmentOrder> find(UUID orderId) {
        return orders.findById(new OrderId(orderId)).map(InstallmentOrdersAdapter::toInstallmentOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<java.time.Instant> deliveredInFullAt(UUID orderId) {
        return orders.findById(new OrderId(orderId))
                .filter(order -> DELIVERED_IN_FULL.contains(order.status()))
                .map(Order::deliveredAt);
    }

    /**
     * Every shipment the order knows of left with a carrier - the order hears of one only at its handover - so all of
     * them count, delivered, failed, brought back or lost alike: unlike {@link HandedOverBatches}, which leaves out what
     * a closure may still close, this is what a receipt paid before handover let go (WF-05 Q-20).
     */
    @Override
    @Transactional(readOnly = true)
    public HandedOverGoods handedOverGoods(UUID orderId) {
        return orders.findById(new OrderId(orderId))
                .filter(order -> !order.shipments().isEmpty())
                .map(order -> new HandedOverGoods(true, order.shipments().stream()
                        .map(OrderShipment::deliveryBatchId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet())))
                .orElse(HandedOverGoods.NONE);
    }

    private static InstallmentOrder toInstallmentOrder(Order order) {
        return new InstallmentOrder(order.id().value(), order.orderNumber().value(), order.customerId(),
                order.status().name(), order.total().currency().getCurrencyCode(), order.status().mayBePutOnHold());
    }
}
