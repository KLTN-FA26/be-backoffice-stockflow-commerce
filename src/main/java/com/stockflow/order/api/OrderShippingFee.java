package com.stockflow.order.api;

import java.time.Instant;
import java.util.List;
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
 * @param plateFees       the order's plate fees above zero, each with the VAT rate of the day asked about - only from
 *                        {@link DeliveredShipments#orderFees} (WF-05 BE-62), empty from {@code shippingFee}. Null
 *                        {@code fee} there means the order has plate fees and no shipping fee
 * @param shippingFeeWaiver the part of the shipping fee waived for shipments of the order that ended undelivered
 *                          (WF-05 Q-19, {@link ShippingFeeWaiver}); {@link ShippingFeeWaiver#NONE} when none did
 */
public record OrderShippingFee(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                               String currency, DeliveredShipment.Fee fee, long orderUnits, Instant lastDeliveredAt,
                               List<DeliveredShipment.Fee> plateFees, ShippingFeeWaiver shippingFeeWaiver) {

    public OrderShippingFee {
        plateFees = plateFees == null ? List.of() : List.copyOf(plateFees);
        shippingFeeWaiver = shippingFeeWaiver == null ? ShippingFeeWaiver.NONE : shippingFeeWaiver;
    }

    /** No part of the shipping fee waived. */
    public OrderShippingFee(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                            String currency, DeliveredShipment.Fee fee, long orderUnits, Instant lastDeliveredAt,
                            List<DeliveredShipment.Fee> plateFees) {
        this(orderId, orderNumber, customerId, contractNumber, currency, fee, orderUnits, lastDeliveredAt, plateFees,
                ShippingFeeWaiver.NONE);
    }

    /** The shipping fee alone, as {@link DeliveredShipments#shippingFee} answers. */
    public OrderShippingFee(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                            String currency, DeliveredShipment.Fee fee, long orderUnits, Instant lastDeliveredAt) {
        this(orderId, orderNumber, customerId, contractNumber, currency, fee, orderUnits, lastDeliveredAt, List.of());
    }
}
