package com.stockflow.order.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An order's shipping fee as a whole, for the invoice that carries what its deliveries' invoices did not (WF-05 E2,
 * {@link DeliveredShipments#shippingFee}): the rest of the fee once the order's undelivered rest was closed and no
 * delivery is left to carry it.
 *
 * @param contractNumber  the order's live sales contract; null for an order without one
 * @param fee             the order's shipping fee line, whole, with the VAT rate of the day of {@code lastDeliveredAt}
 *                        (of the day asked about when nothing was delivered)
 * @param orderUnits      the pieces the order means to deliver as it stands now: its lines less what was closed. Once
 *                        the rest is closed, that is what its shipments still with the carrier or the customer carry
 * @param lastDeliveredAt when the order's last delivered shipment reached the customer - the end of the carrying the
 *                        fee pays for; null when none of its shipments is delivered
 */
public record OrderShippingFee(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                               String currency, DeliveredShipment.Fee fee, long orderUnits, Instant lastDeliveredAt) {
}
