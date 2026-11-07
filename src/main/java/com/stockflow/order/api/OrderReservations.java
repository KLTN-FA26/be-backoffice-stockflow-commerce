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
}
