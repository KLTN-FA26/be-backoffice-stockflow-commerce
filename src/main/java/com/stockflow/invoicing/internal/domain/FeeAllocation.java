package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How an order's fee lines are spread over the invoices of its deliveries (plan §1.4 "dòng phí"; owner's decision
 * 12/11/2026, which replaces kltn-docs 21 §4.1's assumption that every fee goes on the first invoice).
 *
 * <p><b>Plate fees go on the invoice of the order's first delivery</b>, whole: the plates were made before anything
 * was printed, and a fee split over deliveries would leave the customer invoiced for part of a plate.</p>
 *
 * <p><b>The shipping fee is invoiced delivery by delivery.</b> An order has one shipping fee line for all of its
 * deliveries (14 BR-20), not one per delivery, so each delivery carries its share by the pieces it delivered. The
 * share is the difference of two running totals - the fee owed for everything delivered up to and including this
 * shipment, less the fee owed for everything before it - each rounded to the dong. Rounding the running total rather
 * than each share is what makes the shares of a fully delivered order add up to the fee exactly, and makes a
 * shipment's share depend only on where it stands among the deliveries, not on which invoices exist: drawing a voided
 * draft up again gives the same line.</p>
 */
public final class FeeAllocation {

    private FeeAllocation() {
    }

    /** Whether this delivery is the order's first, whose invoice carries the plate fees. */
    public static boolean carriesPlateFees(long unitsDeliveredBefore) {
        return unitsDeliveredBefore <= 0;
    }

    /**
     * This delivery's share of the order's shipping fee, in whole dong; 0 when it has none.
     *
     * @param fee                  the order's shipping fee before tax
     * @param orderUnits           the pieces the order means to deliver
     * @param unitsDeliveredBefore the pieces delivered before this shipment
     * @param unitsInShipment      the pieces this shipment delivered
     */
    public static BigDecimal shippingShare(BigDecimal fee, long orderUnits, long unitsDeliveredBefore,
                                           long unitsInShipment) {
        if (fee == null || fee.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (orderUnits <= 0) {
            // Nothing to measure a share by: the first delivery takes the fee, as a single delivery would.
            return unitsDeliveredBefore <= 0 ? fee.setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
        // Deliveries can outrun what the order still means to deliver once part of it was closed: never past the fee.
        long before = Math.min(Math.max(unitsDeliveredBefore, 0), orderUnits);
        long through = Math.min(before + Math.max(unitsInShipment, 0), orderUnits);
        return owedThrough(fee, through, orderUnits).subtract(owedThrough(fee, before, orderUnits));
    }

    private static BigDecimal owedThrough(BigDecimal fee, long units, long orderUnits) {
        return fee.multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(orderUnits), 0, RoundingMode.HALF_UP);
    }
}
