package com.stockflow.order.api;

import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the order module.
 *
 * <p>Narrow on purpose. Other modules need to place an order and to look one up; they do not need
 * to add a line, change an address or transition a status, so those are not here. Every method
 * added to this interface is a coupling point that can never be removed without touching every
 * caller.</p>
 */
public interface OrderService {

    /**
     * Place an order: create it, reserve the stock, publish the fact.
     *
     * <p>All of it in one transaction. See {@code OrderServiceImpl} for why that sentence is the
     * entire justification for this architecture.</p>
     */
    OrderSummary placeOrder(PlaceOrderCommand command);

    Optional<OrderSummary> findById(UUID orderId);

    /** Cancel an order and release whatever stock it was holding. */
    void cancel(UUID orderId, String reason);
}
