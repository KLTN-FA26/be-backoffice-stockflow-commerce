package com.stockflow.order.api;

import java.math.BigDecimal;

/**
 * What of an order's shipping fee is waived because shipments of the order ended undelivered - brought back by the
 * carrier or lost (WF-05 decision Q-19, owner 2026-11-23: the fee in proportion to those shipments' goods value, all of
 * it once every shipment did). Payment works the share out and waives it with VAT as each such shipment's money is
 * settled ({@code OrderSettlement.shippingFeeShare}, returned as {@code OrderSettlementResult.shippingFeeWaived}); the
 * order keeps the figure, and invoicing - which cannot read payment - invoices the fee less it, from this one figure
 * rather than a second copy of payment's rule ({@code FeeAllocation.shippingFeeOwed}).
 *
 * @param waived   the order's total waived so far, with VAT, whole dong: the sum over its shipments closed undelivered
 * @param feeGross the order's shipping fee line with its VAT, as the order fixed it - what {@code waived} is a share of
 */
public record ShippingFeeWaiver(BigDecimal waived, BigDecimal feeGross) {

    /** Nothing waived: no shipment of the order ended undelivered, or the order has no shipping fee. */
    public static final ShippingFeeWaiver NONE = new ShippingFeeWaiver(BigDecimal.ZERO, BigDecimal.ZERO);

    public ShippingFeeWaiver {
        waived = waived == null ? BigDecimal.ZERO : waived;
        feeGross = feeGross == null ? BigDecimal.ZERO : feeGross;
        if (waived.signum() < 0 || feeGross.signum() < 0) {
            throw new IllegalArgumentException("A waiver and the fee it is of are zero or more");
        }
    }
}
