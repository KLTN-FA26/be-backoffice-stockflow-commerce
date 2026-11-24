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
 * <p><b>An order closed with nothing more to deliver</b> may have had every delivery invoiced already, each at its share
 * of the pieces the order then meant to deliver: no delivery is left to take the rest of the fee. The closing step
 * puts that rest on an invoice of its own ({@link #shippingRest}); while a delivery is still to be invoiced - in
 * transit, or delivered and not yet drawn up - that delivery takes it as above, and the closing step draws nothing.</p>
 */
public final class FeeAllocation {

    /**
     * What the order's invoices in force already carry - every original that was not voided.
     *
     * @param units         the pieces on their goods lines
     * @param shippingFee   the shipping fee on their fee lines, before tax
     * @param plateFeeSkus  the SKUs whose plate fee is on one of them
     * @param goodsValue    what their goods lines come to before tax (WF-05 Q-19: the fee is shared by value)
     */
    public record Invoiced(long units, BigDecimal shippingFee, Set<String> plateFeeSkus, BigDecimal goodsValue) {

        public static final Invoiced NOTHING = new Invoiced(0, BigDecimal.ZERO, Set.of(), BigDecimal.ZERO);

        public Invoiced {
            shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
            plateFeeSkus = plateFeeSkus == null ? Set.of() : Set.copyOf(plateFeeSkus);
            goodsValue = goodsValue == null ? BigDecimal.ZERO : goodsValue;
        }

        /** Without the goods' value: shares are then measured in pieces only. */
        public Invoiced(long units, BigDecimal shippingFee, Set<String> plateFeeSkus) {
            this(units, shippingFee, plateFeeSkus, BigDecimal.ZERO);
        }
    }

    private FeeAllocation() {
    }

    /**
     * The part of the order's shipping fee, before tax, its invoices carry (WF-05 decision Q-19, owner 2026-11-23): the
     * fee less the share waived because shipments of the order ended undelivered. Payment waives that share with VAT
     * ({@code waivedGross}, a share of the fee line with VAT {@code feeGross}); the invoices carry
     * {@code feeNet - round_half_up(feeNet x waivedGross / feeGross)}, the same share before tax - so a whole return
     * leaves nothing and the rest invoice carries plate fees only. With VAT on top the invoices come to what payment
     * keeps within the rounding of the VAT of each fee line: half a dong a line.
     *
     * <p>What {@link #shippingShareByValue}, {@link #shippingShare} and {@link #shippingRest} share out, for every
     * caller alike - a delivery's invoice, the invoice of the order's rest, and the check whether that rest is still
     * owed - or they would disagree on what is left and an order would wait for good on a rest nobody draws up.</p>
     */
    public static BigDecimal shippingFeeOwed(BigDecimal feeNet, BigDecimal feeGross, BigDecimal waivedGross) {
        if (feeNet == null || feeNet.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal net = feeNet.setScale(0, RoundingMode.HALF_UP);
        if (waivedGross == null || waivedGross.signum() <= 0 || feeGross == null || feeGross.signum() <= 0) {
            return net;
        }
        if (waivedGross.compareTo(feeGross) >= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal waivedNet = net.multiply(waivedGross).divide(feeGross, 0, RoundingMode.HALF_UP);
        return net.subtract(waivedNet).max(BigDecimal.ZERO);
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

    /**
     * This delivery's share of the order's shipping fee by the value of the goods it delivered (WF-05 Q-19), in whole
     * dong; 0 when it has none. As {@link #shippingShare}, with value in place of pieces: what the order owes for
     * everything invoiced so far and this delivery together, less what its invoices already carry; the delivery that
     * completes the order takes the rest.
     *
     * <p><b>Why value.</b> Payment waives the fee of a shipment that came back by its goods' value
     * ({@code OrderSettlement.shippingFeeShare}). Shared by pieces, a delivery of many cheap pieces invoiced before a
     * shipment of a few dear ones came back carried more of the fee than payment keeps - and an issued invoice takes
     * nothing back. Shared by value, each delivered shipment carries {@code fee x its value / the order's value}
     * whatever came back before or after, which is the fee payment keeps for it: the invoices never carry more than is
     * collected, beyond rounding.</p>
     *
     * @param fee           the fee the order's invoices carry, before tax ({@link #shippingFeeOwed})
     * @param orderValue    what the goods the order means to deliver as it stands now are worth before tax
     * @param shipmentValue what this shipment's goods are worth before tax
     */
    public static BigDecimal shippingShareByValue(BigDecimal fee, BigDecimal orderValue, BigDecimal shipmentValue,
                                                  Invoiced invoiced) {
        if (fee == null || fee.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal whole = fee.setScale(0, RoundingMode.HALF_UP);
        BigDecimal through = invoiced.goodsValue().max(BigDecimal.ZERO)
                .add(shipmentValue == null ? BigDecimal.ZERO : shipmentValue.max(BigDecimal.ZERO));
        BigDecimal owed = orderValue == null || orderValue.signum() <= 0 || through.compareTo(orderValue) >= 0 ? whole
                : whole.multiply(through).divide(orderValue, 0, RoundingMode.HALF_UP);
        return owed.subtract(invoiced.shippingFee()).max(BigDecimal.ZERO);
    }

    /**
     * The rest of the order's shipping fee no delivery will carry, in whole dong: what the order's invoices lack of the
     * whole fee, once every piece the order still means to deliver is on one of them. 0 otherwise.
     *
     * <p>Two cases draw nothing, on purpose. While the invoices carry fewer pieces than the order means to deliver, a
     * delivery is still to be invoiced - on its way, or delivered and not yet drawn up - and {@link #shippingShare}
     * gives it the rest when it is, so drawing it here as well would carry the money twice. And an order none of whose
     * pieces is on an invoice delivered nothing the fee was for: whether a fee is owed for goods that never arrived is
     * settled on the order's installments when its rest is closed, not by an invoice.</p>
     *
     * @param fee        the order's shipping fee before tax
     * @param orderUnits the pieces the order means to deliver, as it stands now
     * @param invoiced   what the order's invoices in force already carry
     */
    public static BigDecimal shippingRest(BigDecimal fee, long orderUnits, Invoiced invoiced) {
        if (fee == null || fee.signum() <= 0 || invoiced.units() <= 0 || invoiced.units() < orderUnits) {
            return BigDecimal.ZERO;
        }
        return fee.setScale(0, RoundingMode.HALF_UP).subtract(invoiced.shippingFee()).max(BigDecimal.ZERO);
    }
}
