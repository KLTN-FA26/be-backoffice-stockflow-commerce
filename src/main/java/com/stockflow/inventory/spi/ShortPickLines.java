package com.stockflow.inventory.spi;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Asks the module that keeps pick lines which holds a picker has already reported short (kltn-docs 07 §4.4 step 9).
 * Implemented by {@code fulfillment}; see the package documentation for why the interface lives here.
 */
public interface ShortPickLines {

    /**
     * Which of these holds (allocations) are those of a pick line that is SHORT: its carton was reported not found or
     * damaged, its order is held for it and waits for the warehouse manager to reallocate the line. A plain read; it
     * takes no lock.
     *
     * @param reservationIds ids of allocations on stock rows
     * @return the ids among them whose pick line is SHORT; empty when none is
     */
    Set<UUID> shortHolds(Collection<UUID> reservationIds);
}
