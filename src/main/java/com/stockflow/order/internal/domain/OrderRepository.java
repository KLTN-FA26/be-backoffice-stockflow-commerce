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

    /** Idempotency: a resubmitted checkout must return the original order, not create a second. */
    Optional<Order> findByRequestId(UUID requestId);

    List<Order> findByCustomerId(UUID customerId);

    OrderNumber nextOrderNumber(LocalDate date);

    Order save(Order order);
}
