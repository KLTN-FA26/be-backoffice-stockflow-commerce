package com.stockflow.order.api;

import java.util.UUID;

/**
 * A return request, as a return receipt names it (team plan 4D-2).
 *
 * @param awaitingGoods Sales approved it and its goods have not come back yet ({@code AWAITING_RETURN}): the only time
 *                      the warehouse opens a receipt for it
 * @param status        its status as the order module names it, for a message that says why not
 */
public record ReturnRequestReference(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                     String status, boolean awaitingGoods) {
}
