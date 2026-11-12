package com.stockflow.order.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An order's shipping fee as a whole, for the invoice that carries what its deliveries' invoices did not (WF-05 E2,
 * {@link DeliveredShipments#shippingFee}): the rest of the fee once the order's undelivered rest was closed and no
 * delivery is left to carry it.
 *
 * @param contractNumber  the order's live sales contract; null for an order without one
 * @param fee             the order's shipping fee line, whole, with the VAT rate of the day asked about (the day the
 *                        order's rest was closed: the date of the fee's invoice)
 * @param orderUnits      the pieces the order means to deliver as it stands now: its lines less what was closed. Once
 *                        the rest is closed, that is what its shipments still with the carrier or the customer carry
 * @param lastDeliveredAt when the order's last delivered shipment reached the customer; null when none of its
 *                        shipments is delivered. A fact about the order only: the fee's invoice is not dated by it
 */
public record OrderShippingFee(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                               String currency, DeliveredShipment.Fee fee, long orderUnits, Instant lastDeliveredAt) {
}
