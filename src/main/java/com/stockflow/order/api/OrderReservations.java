package com.stockflow.order.api;

import java.util.Collection;
import java.util.UUID;

/**
 * Holds an order line gains after checkout (SCRUM-400): the finished goods of a reprint round or a subcontracted
 * child, which production held while it made them and hands to the order on completion. Without them on the
 * line, cancelling the order would release only the checkout holds and leave these held for nobody, and
 * fulfilment would see only part of the goods.
 */
public interface OrderReservations {

    /**
     * Adds the holds to the line, in the caller's transaction, under the order's lock. Idempotent: a hold already
     * on the line is not added twice.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} for an unknown order or line
     */
    void addProductionHolds(UUID orderId, UUID orderLineId, Collection<UUID> reservationIds);

    /**
     * A hold of the order was allocated again elsewhere (X-SHORT, SCRUM-152): the line holding
     * {@code reservationId} holds {@code replacements} instead, which a cancellation then releases. In the caller's
     * transaction, under the order's lock. Idempotent: a hold no longer on the order changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} for an unknown order
     */
    void replaceHold(UUID orderId, UUID reservationId, Collection<UUID> replacements);
}
