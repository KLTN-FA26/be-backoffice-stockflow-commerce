package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * How an order's fee lines are spread over the invoices of its deliveries (plan §1.4 "dòng phí"; owner's decision
 * 12/11/2026, which replaces kltn-docs 21 §4.1's assumption that every fee goes on the first invoice).
 *
 * <p><b>Everything is decided from the invoices the order already has</b> ({@link Invoiced}), under the order's lock -
 * never from the times the carrier gave for each delivery. Those times do not only grow: a delivery that happened
 * earlier can be recorded later, and an invoice drawn up from "nothing was delivered before this" would then be
 * followed by another saying the same, with the plate fee on both. What the order's invoices carry only ever grows, so
 * it is the one thing two drafts cannot both be first against.</p>
 *
 * <p><b>A plate fee goes on the first invoice drawn up</b>, whole: on this invoice unless an invoice of the order
 * already carries the plate fee of that SKU.</p>
 *
 * <p><b>The shipping fee is invoiced delivery by delivery.</b> An order has one shipping fee line for all of its
 * deliveries (14 BR-20), so each delivery carries a share by the pieces delivered. The share is what the order owes
 * for everything invoiced so far and this delivery together, less what its invoices already carry. Two consequences,
 * both wanted:</p>
 * <ul>
 *   <li>the delivery that completes the order - nothing left to deliver - takes whatever is left of the fee, so the
 *       invoices add up to the fee to the dong, whatever the rounding and whatever was closed in between;</li>
 *   <li>when part of the order is closed after a delivery, the pieces the order means to deliver shrink and the next
 *       delivery's share makes up the difference, instead of the fee coming up short.</li>
 * </ul>
 *
 * <p>An order closed with nothing more delivered leaves the rest of its shipping fee on no invoice: there is no
 * delivery to carry it. That is the closing step's to settle, not a delivery's.</p>
 */
public final class FeeAllocation {

    /**
     * What the order's invoices in force already carry - every original that was not voided.
     *
     * @param units         the pieces on their goods lines
     * @param shippingFee   the shipping fee on their fee lines, before tax
     * @param plateFeeSkus  the SKUs whose plate fee is on one of them
     */
    public record Invoiced(long units, BigDecimal shippingFee, Set<String> plateFeeSkus) {

        public static final Invoiced NOTHING = new Invoiced(0, BigDecimal.ZERO, Set.of());

        public Invoiced {
            shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
            plateFeeSkus = plateFeeSkus == null ? Set.of() : Set.copyOf(plateFeeSkus);
        }
    }

    private FeeAllocation() {
    }

    /** Whether this invoice carries the plate fee of {@code sku}: no invoice of the order carries it yet. */
    public static boolean carriesPlateFee(String sku, Invoiced invoiced) {
        return !invoiced.plateFeeSkus().contains(sku);
    }

    /**
     * This delivery's share of the order's shipping fee, in whole dong; 0 when it has none.
     *
     * @param fee             the order's shipping fee before tax
     * @param orderUnits      the pieces the order means to deliver, as it stands now
     * @param unitsInShipment the pieces this shipment delivered
     * @param invoiced        what the order's invoices in force already carry
     */
    public static BigDecimal shippingShare(BigDecimal fee, long orderUnits, long unitsInShipment, Invoiced invoiced) {
        if (fee == null || fee.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal whole = fee.setScale(0, RoundingMode.HALF_UP);
        long through = Math.max(invoiced.units(), 0) + Math.max(unitsInShipment, 0);
        // Everything the order means to deliver is invoiced with this delivery (or there is nothing to measure a
        // share by): the whole fee is owed, and this invoice takes what the others have not.
        BigDecimal owed = orderUnits <= 0 || through >= orderUnits ? whole
                : whole.multiply(BigDecimal.valueOf(through)).divide(BigDecimal.valueOf(orderUnits), 0,
                        RoundingMode.HALF_UP);
        // Never below zero: invoices drawn up before part of the order was closed may already carry more than is
        // owed against what is left, and an original takes nothing back.
        return owed.subtract(invoiced.shippingFee()).max(BigDecimal.ZERO);
    }
}
