package com.stockflow.order.spi;

import java.util.List;
import java.util.UUID;

/**
 * Asks the module that runs the pick tasks which cartons of an order's released batch the warehouse is short of (kltn-docs
 * 07 §4.4 steps 9-11, 17 §4.14 step 55): the lines reported short, and the cartons the check station removed that nothing
 * replaced. The shortage choice card is drawn from this answer - Sales never type it (16 BR-15). Implemented by
 * {@code fulfillment}; see the package documentation for why the interface lives here.
 *
 * <p>Read-only. The operations that carry a customer's choice out - locking the tasks, cancelling the short lines - are
 * added to this interface by the pull request that executes the choice.</p>
 */
public interface ShortPickLines {

    /**
     * Cartons of one order line that are missing.
     *
     * @param orderLineId    the order line
     * @param sku            its SKU
     * @param missingCartons how many whole cartons: more than zero
     */
    record ShortLine(UUID orderLineId, String sku, int missingCartons) {
    }

    /**
     * Per order line, the cartons the released batch is short of: pick lines reported SHORT, and cartons removed at the
     * check station (damaged, or out of a shipment cancelled before dispatch) that no replacement covers yet. Runs in
     * the caller's transaction.
     *
     * @return empty when nothing is short; lines with nothing missing are left out
     */
    List<ShortLine> shortLinesOf(UUID orderId);
}
