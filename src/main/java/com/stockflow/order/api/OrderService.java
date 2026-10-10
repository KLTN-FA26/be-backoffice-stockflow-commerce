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

    /**
     * Cancel an order and release whatever stock it was holding; {@code OrderCancelled} tells payment,
     * fulfillment and production (SCRUM-460).
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_NOT_CANCELLABLE} from SHIPPED on
     */
    OrderSummary cancel(UUID orderId, CancelOrderCommand command);

    /** {@link #cancel(UUID, CancelOrderCommand)} with a free-text reason, read by {@link CancelOrderCommand#fromText}. */
    void cancel(UUID orderId, String reason);

    /**
     * A customer cancelling their own order: at once while nothing has been released, otherwise as a
     * request Sales decides (kltn-docs 17 §2, §4.4). {@code customerId} is the customer profile id
     * that orders are stored under, not the sign-in id, so the ownership check is made here
     * explicitly rather than through the ambient data scope (which compares against the sign-in id).
     *
     * @param requestedBy the signed-in user, recorded on a request
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} if the order is not this
     *         customer's, so the existence of another customer's order is not revealed;
     *         {@code ORDER_CANCELLATION_REQUEST_PENDING} when a request is already waiting
     */
    CancellationOutcome cancelOwn(UUID orderId, UUID customerId, CancellationReasonCode reasonCode, String note,
                                  UUID requestedBy);

    OrderSummary releaseToFulfillment(UUID orderId);
    void putOnDesignHold(UUID orderId, String reason);
    void resolveDesignHold(UUID orderId, UUID resolvedBy, String note);

    /**
     * SCRUM-245/WBS 3.17.7. One customer's own order history, newest first, paginated. {@code
     * lines} is empty on every row — see {@code OrderSearchRepository} for why; a single order's
     * lines are available from {@link #findById}.
     */
    PageResponse<OrderSummary> myOrders(UUID customerId, int page, int size);

    /** The back-office list: every order the caller may see, filtered and paged (SCRUM-443). */
    PageResponse<OrderSummary> list(ListOrdersQuery query);

    /**
     * Status history, oldest first. Orders placed before history was recorded start at the first
     * transition after that; an order with no recorded change returns an empty list.
     */
    java.util.List<OrderStatusChange> history(UUID orderId);
}
