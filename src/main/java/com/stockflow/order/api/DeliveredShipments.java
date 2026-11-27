package com.stockflow.order.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The order's delivered shipments, as the e-invoice reads them (WF-05 E2). Read only: an invoice changes nothing about
 * an order (kltn-docs 21 BR-11).
 */
public interface DeliveredShipments {

    /**
     * The shipment as delivered; empty when the order or the shipment does not exist, or the shipment is not DELIVERED
     * - never was, or its delivery was corrected to a failure since (09 §4.3 step 5): nothing is invoiced then.
     */
    Optional<DeliveredShipment> find(UUID orderId, UUID shipmentId);

    /**
     * The order's shipping fee, whole, and when the carrying it pays for ended (its last delivery); empty when the
     * order does not exist or has no shipping fee above zero. The VAT rate is the one in force on the day of the last
     * delivery - the day the invoice of the fee is dated - or on the day of {@code on} when nothing was delivered. How
     * much of the fee the order's invoices already carry is the invoicing module's to say.
     */
    Optional<OrderShippingFee> shippingFee(UUID orderId, Instant on);

    /**
     * Every fee of the order - its shipping fee ({@link OrderShippingFee#fee}, null when it has none above zero) and its
     * plate fees ({@link OrderShippingFee#plateFees}) - with the VAT rates of the day of {@code on}; empty when the
     * order does not exist or has no fee above zero (WF-05 BE-62, Q-19: a plate fee no delivery's invoice carries -
     * the order's goods all came back - is invoiced with the rest of its fees once its deliveries are over).
     */
    Optional<OrderShippingFee> orderFees(UUID orderId, Instant on);
}
