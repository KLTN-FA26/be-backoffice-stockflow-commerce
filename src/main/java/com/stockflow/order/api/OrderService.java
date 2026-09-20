package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;

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

    /** Guest checkout with immutable contact/address snapshots and no customer account. */
    OrderSummary placeGuestOrder(PlaceGuestOrderCommand command);

    Optional<OrderSummary> findById(UUID orderId);

    /** Cancel an order and release whatever stock it was holding. */
    void cancel(UUID orderId, String reason);

    OrderSummary releaseToFulfillment(UUID orderId);
    void putOnDesignHold(UUID orderId, String reason);
    void resolveDesignHold(UUID orderId, UUID resolvedBy, String note);

    /**
     * SCRUM-245/WBS 3.17.7. One customer's own order history, newest first, paginated. {@code
     * lines} is empty on every row — see {@code OrderSearchRepository} for why; a single order's
     * lines are available from {@link #findById}.
     */
    PageResponse<OrderSummary> myOrders(UUID customerId, int page, int size);
}
