package com.stockflow.order.api;

import java.util.Collection;
import java.util.UUID;

/**
 * Holds an order line gains or swaps after checkout: inventory moving an allocation to other bins after a short pick
 * (X-SHORT, SCRUM-152). Without the new holds on the line, cancelling the order would release only the old ones and
 * leave these held for nobody.
 */
public interface OrderReservations {

    /**
     * A hold of the order was allocated again elsewhere (X-SHORT, SCRUM-152): the line holding
     * {@code reservationId} holds {@code replacements} instead, which a cancellation then releases. In the caller's
     * transaction, under the order's lock. Idempotent: a hold no longer on the order changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} for an unknown order
     */
    void replaceHold(UUID orderId, UUID reservationId, Collection<UUID> replacements);

    /**
     * The carton {@code reservationId} took was removed from the order at the check station as damaged (kltn-docs 08
     * §4.2 step 4): it is a shortage of its order line, handled like a short pick (07 §4.4). The hold - picked, so
     * CONSUMED - no longer stands for its delivery batch, which lacks that carton again (17 §5.5: Ready → Awaiting
     * Stock when a carton is removed), so the batch's coverage, the production schedule and a production order for the
     * batch see the shortage. The hold stays on its line: its carton goes into quarantine through it. Idempotent; a hold
     * that stands for no batch changes nothing. In the caller's transaction.
     */
    void cartonRemoved(UUID orderId, UUID reservationId);

    /**
     * The warehouse manager allocated a replacement for a carton removed at the check station (07 §4.4 step 10): the
     * line {@code orderLineId} holds {@code reservationIds} too - which the task picks and a cancellation releases -
     * and they stand for {@code deliveryBatchId} (null for an order released without a delivery schedule). In the
     * caller's transaction, under the order's lock. Idempotent.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} for an unknown order
     */
    void holdReplacement(UUID orderId, UUID orderLineId, UUID deliveryBatchId, Collection<UUID> reservationIds);
}
