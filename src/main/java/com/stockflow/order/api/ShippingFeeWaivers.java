package com.stockflow.order.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What of an order's shipping fee was waived because its goods did not reach the customer (WF-05 Q-19, owner
 * 23/11/2026): each shipment the carrier brought back, or Sales closed as lost, waives its share of the order's goods,
 * as payment worked it out when it settled the shipment ({@code OrderSettlementResult.shippingFeeWaived}); and when the
 * order ends with nothing delivered after a carrier return, whichever comes last - the shipment, or the closure of the
 * rest before it shipped - waives what is left (Q-19 edge, owner 23/11). The order keeps payment's answers, on the
 * shipment and on the closure ({@code order_shipment.shipping_fee_waived}, {@code order_remainder_closure
 * .shipping_fee_waived}). For invoicing (BE-62):
 * the order's invoices carry the shipping fee less this, without a second copy of payment's rule.
 */
public interface ShippingFeeWaivers {

    /**
     * The order's shipping fee waived so far, with its VAT, in whole units of its currency: the sum over its shipments
     * whose units were closed undelivered and its closures of the rest. Zero for an order with none, no shipping fee
     * line, or unknown. Read in the caller's transaction; a closure and its waiver are written in one transaction, under
     * the order's lock.
     */
    BigDecimal waivedOf(UUID orderId);
}
