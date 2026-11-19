package com.stockflow.inventory.spi;

import java.util.Collection;
import java.util.Set;

/**
 * Asks the module that picks and ships which consumed cartons are still at the warehouse for an order: picked for it
 * (a picked line of a task not cancelled), not removed at the check station, and not handed over yet - in no shipment,
 * or in one still waiting at dispatch for its load. Implemented by {@code fulfillment}; see the package documentation
 * for why the interface lives here.
 */
public interface PickedNotHandedOver {

    /**
     * Runs in the caller's transaction.
     *
     * @param lpns normalised LPNs (upper case, trimmed)
     * @return those of {@code lpns} that are such cartons; empty when none is
     */
    Set<String> amongst(Collection<String> lpns);
}
