package com.stockflow.order.spi;

import java.util.List;
import java.util.UUID;

/**
 * Asks the module that runs the pick tasks which cartons of an order's released batch the warehouse is short of (kltn-docs
 * 07 §4.4 steps 9-11, 17 §4.14 step 55): the lines reported short, and the cartons the check station removed that nothing
 * replaced. The shortage choice card is drawn from this answer - Sales never type it (16 BR-15). Implemented by
 * {@code fulfillment}; see the package documentation for why the interface lives here.
 *
 * <p>Reading is a plain read. Carrying a customer's choice out (17 §4.14 step 58) takes the order's pick tasks first -
 * {@link #lockTasksOf}, before the order row, as every writer of a pick does (pick, then order, then cartons; BE
 * {@code CLAUDE.md} §4 Picking) - and then gives the short lines up ({@link #cancelShortLines}).</p>
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

    /**
     * Locks every pick task of the order {@code FOR UPDATE}, in id order. Called first by the operation that carries a
     * shortage choice out, <em>before</em> the order row is locked: a picker's confirmation or short report and the
     * warehouse manager's reallocation lock the pick and then the order, and taking them the other way round would
     * deadlock against them. Runs in the caller's transaction; nothing changes.
     */
    void lockTasksOf(UUID orderId);

    /**
     * Gives the order's SHORT pick lines up (17 §4.14 step 58, D49; 07 §4.4 step 10, BR-09): each line is CANCELLED - its
     * shortfall stays on record - and the hold on its missing carton is released ({@code SHORT_PICKED}, what a
     * reallocation does to it), so the order line no longer lists it and its delivery batch lacks the carton again, for a
     * production order to make or for the rest to be closed. The carton itself stays blocked by its open blind count until
     * the variance is decided (20 BR-11). A carton removed at the check station is not a SHORT line: its hold was
     * consumed and the carton goes to quarantine through it, and its batch already lacks it - nothing to give up. Runs in
     * the caller's transaction, after {@link #lockTasksOf} and the order's lock. Idempotent: nothing is SHORT the second
     * time.
     *
     * @param by who carries the choice out
     * @return how many lines were given up
     */
    int cancelShortLines(UUID orderId, UUID by);
}
