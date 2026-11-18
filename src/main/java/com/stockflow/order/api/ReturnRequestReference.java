package com.stockflow.order.api;

import java.util.UUID;

/**
 * A return request, as a return receipt names it (team plan 4D-2).
 *
 * @param awaitingGoods Sales approved it and its goods have not all come back yet ({@code AWAITING_RETURN}): the only
 *                      time the warehouse opens a receipt for it - one per parcel, while part is still awaited (WF-05
 *                      TH-3)
 * @param status        its status as the order module names it, for a message that says why not
 * @param shipmentId    the delivered shipment it is against (WF-05 TH-1): only that shipment's cartons come back on its
 *                      receipt. Null for a return of an order delivered without shipments, whose cartons are any its
 *                      deliveries carried
 */
public record ReturnRequestReference(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                     String status, boolean awaitingGoods, UUID shipmentId) {

    /** Against the order as a whole. */
    public ReturnRequestReference(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                  String status, boolean awaitingGoods) {
        this(id, rmaNumber, orderId, orderNumber, customerId, status, awaitingGoods, null);
    }
}
