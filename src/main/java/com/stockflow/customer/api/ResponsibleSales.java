package com.stockflow.customer.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * THE rule for "which salesperson deals with this customer" (sales-owner plan §2.2): chat routing and every notice ask
 * it instead of working it out from a document.
 *
 * <pre>
 *   owner ACTIVE and a cover in force on the day  -> COVER    (the stand-in acts)
 *   owner ACTIVE                                  -> OWNER
 *   no owner, owner left, or owner's account not ACTIVE
 *                                                 -> MANAGERS (active Sales Managers, else System Admins)
 * </pre>
 *
 * <p>Whether the owner works is read from the account each time, not only from the customer's state: between a lock
 * and the listener that marks the customers, a locked owner already counts as gone.</p>
 */
public interface ResponsibleSales {

    /**
     * One batched read: a constant handful of statements however many ids. Never throws for an unknown id: it is left
     * out of the answer. Null ids are ignored.
     *
     * @param businessDay the day a cover must be in force on (the business calendar's today, as a rule)
     */
    Map<UUID, Responsible> responsibleFor(Collection<UUID> customerIds, LocalDate businessDay);

    /** {@link #responsibleFor(Collection, LocalDate)} for one customer; empty for an unknown one. */
    default Optional<Responsible> responsibleFor(UUID customerId, LocalDate businessDay) {
        if (customerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(responsibleFor(java.util.List.of(customerId), businessDay).get(customerId));
    }
}
