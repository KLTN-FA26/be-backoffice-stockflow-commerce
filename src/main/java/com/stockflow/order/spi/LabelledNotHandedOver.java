package com.stockflow.order.spi;

import java.util.Map;
import java.util.UUID;

/**
 * Asks the module that runs the check station which units of an order are in cartons checked and labelled for it and not
 * handed over yet (kltn-docs 08 §4.4, 09 §4.1): in a shipment waiting at dispatch for its load, or labelled at the check
 * station and not in a shipment yet. Implemented by {@code fulfillment}; see the package documentation for why the
 * interface lives here.
 */
public interface LabelledNotHandedOver {

    /**
     * Per order line, the units of the order's labelled cartons not handed over yet. Runs in the caller's transaction.
     *
     * @return empty when the order has none; lines without such a carton are left out
     */
    Map<UUID, Integer> unitsOf(UUID orderId);
}
