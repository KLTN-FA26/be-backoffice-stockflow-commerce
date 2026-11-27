package com.stockflow.order.internal.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the order aggregate.
 *
 * <p>{@link #nextOrderNumber} sits here rather than in a utility class because generating the next
 * number is a persistence concern — it needs a database sequence to stay unique across instances,
 * and no amount of application-side counting can substitute.</p>
 */
public interface OrderRepository {

    /**
     * 4B-7 (17 §4.2 step 3): the orders of a contract with a shipping warehouse whose holds stock arriving later tops
     * up, earliest signed first, so the oldest order is served first - CONFIRMED, AWAITING_STOCK (4A-6), and since 4B-8
     * an order already released whose later delivery batches still hold (READY_TO_FULFILL to PARTIALLY_FULFILLED).
     */
    java.util.List<UUID> waitingForStockAtShippingWarehouse();


    Optional<Order> findById(OrderId id);

    Optional<Order> findByIdForUpdate(OrderId id);

    /**
     * Same lookup as {@link #findByIdForUpdate}, but only when the current {@code DataScope}
     * permits it — empty (not an exception) when the order exists but is outside the caller's
     * scope, so the controller can answer with a 404 rather than confirming the id exists to
     * someone who should not be able to see it.
     *
     * <p>SCRUM-242/WBS 3.17.4: this is what actually enforces "a customer may only cancel their
     * own order" — {@link #findByIdForUpdate} deliberately does not, by design (see {@code
     * OrderJpaRepository}'s own javadoc), because most of its callers already hold a trusted id
     * from outside a request (background listeners). Use this one from any endpoint whose
     * {@code @RequiresPermission} declares a scope narrower than {@code ALL}.</p>
     *
     * <p>It locks the row, so it is for a change only: Postgres refuses {@code SELECT ... FOR UPDATE} in a read-only
     * transaction, and a read takes {@link #findByIdInScopeForReading} (an upload slot answering 500 on every call
     * was this mistake, demo run 24/11 D-1).</p>
     */
    Optional<Order> findByIdInScope(OrderId id);

    /**
     * {@link #findByIdInScope} without the lock, for a read: the order when the current {@code DataScope} lets the
     * caller see it, empty when it does not - so a read-only transaction asks no {@code FOR UPDATE} and an order out of
     * scope reads as absent, as it does to a change.
     */
    Optional<Order> findByIdInScopeForReading(OrderId id);

    /** Idempotency: a resubmitted checkout must return the original order, not create a second. */
    Optional<Order> findByRequestId(UUID requestId);

    List<Order> findByCustomerId(UUID customerId);

    /**
     * When the order first became CONFIRMED, from its status history; empty for an order that never was (or one whose
     * history was not recorded).
     */
    Optional<java.time.Instant> confirmedAt(UUID orderId);

    /**
     * Whether a return request against the shipment is open or resolved - anything but REJECTED, CANCELLED and EXPIRED
     * (WF-05 TH-1: a return names the delivered shipment it is against). Such a delivery is not corrected to a failure
     * ({@code ORDER_DELIVERY_HAS_RETURN}). Read under the order's lock: opening a return takes the same lock.
     */
    boolean hasReturnAgainst(UUID orderId, UUID shipmentId);

    /** The status and hold reason of each order among {@code ids} that exists, in one query, without its lines. */
    List<com.stockflow.order.api.OrderHoldState> holdStates(java.util.Collection<UUID> ids);

    OrderNumber nextOrderNumber(LocalDate date);

    Order save(Order order);
}
