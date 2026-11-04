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
     */
    Optional<Order> findByIdInScope(OrderId id);

    /** Idempotency: a resubmitted checkout must return the original order, not create a second. */
    Optional<Order> findByRequestId(UUID requestId);

    List<Order> findByCustomerId(UUID customerId);

    /**
     * The customer's orders placed at or after {@code since} that are not cancelled, newest first, with their lines:
     * what the duplicate-order check compares a new checkout with (SCRUM-190).
     */
    List<Order> findLiveSince(UUID customerId, java.time.Instant since);

    /** The status and hold reason of each order among {@code ids} that exists, in one query, without its lines. */
    List<com.stockflow.order.api.OrderHoldState> holdStates(java.util.Collection<UUID> ids);

    OrderNumber nextOrderNumber(LocalDate date);

    Order save(Order order);
}
