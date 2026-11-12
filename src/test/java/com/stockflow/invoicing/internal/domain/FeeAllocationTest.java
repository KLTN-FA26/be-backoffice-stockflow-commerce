package com.stockflow.invoicing.internal.domain;

import com.stockflow.invoicing.internal.domain.FeeAllocation.Invoiced;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order's fee lines over the invoices of its deliveries (plan §1.4), decided from what the order's invoices already
 * carry: a plate fee once, the shipping fee adding up to the fee to the dong - whatever the rounding, and whatever was
 * closed in between.
 */
class FeeAllocationTest {

    private static Invoiced invoiced(long units, String shipping, String... plateSkus) {
        return new Invoiced(units, new BigDecimal(shipping), Set.of(plateSkus));
    }

    private static BigDecimal share(String fee, long orderUnits, long inShipment, Invoiced invoiced) {
        return FeeAllocation.shippingShare(new BigDecimal(fee), orderUnits, inShipment, invoiced);
    }

    @Test
    @DisplayName("a plate fee goes on an invoice only while no invoice of the order carries that SKU's")
    void aPlateFeeOnce() {
        assertThat(FeeAllocation.carriesPlateFee("CUP", Invoiced.NOTHING)).isTrue();
        assertThat(FeeAllocation.carriesPlateFee("CUP", invoiced(400, "0", "CUP"))).isFalse();
        assertThat(FeeAllocation.carriesPlateFee("LID", invoiced(400, "0", "CUP")))
                .as("another SKU's plate is another fee").isTrue();
        assertThat(FeeAllocation.carriesPlateFee("CUP", invoiced(400, "40000")))
                .as("invoices without it: it is still to invoice, however many deliveries went before").isTrue();
    }

    @Test
    @DisplayName("one delivery of everything carries the whole shipping fee")
    void oneDeliveryTakesTheWholeFee() {
        assertThat(share("500000", 1000, 1000, Invoiced.NOTHING)).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("deliveries share the fee by pieces, and the shares add up to the fee to the dong")
    void sharesAddUpExactly() {
        // 100,000 over three equal deliveries: 33,333 + 33,334 + 33,333 - the running total is rounded, not each share.
        BigDecimal first = share("100000", 300, 100, Invoiced.NOTHING);
        BigDecimal second = share("100000", 300, 100, invoiced(100, "33333"));
        BigDecimal third = share("100000", 300, 100, invoiced(200, "66667"));
        assertThat(first).isEqualByComparingTo("33333");
        assertThat(second).isEqualByComparingTo("33334");
        assertThat(third).isEqualByComparingTo("33333");

        // Uneven deliveries of an awkward fee: 150, 449 and 101 of 700 pieces.
        BigDecimal a = share("999999", 700, 150, Invoiced.NOTHING);
        BigDecimal b = share("999999", 700, 449, invoiced(150, a.toPlainString()));
        BigDecimal c = share("999999", 700, 101, invoiced(599, a.add(b).toPlainString()));
        assertThat(a).isEqualByComparingTo("214286");
        assertThat(b).isEqualByComparingTo("641427");
        assertThat(c).isEqualByComparingTo("144286");
        assertThat(a.add(b).add(c)).isEqualByComparingTo("999999");
    }

    @Test
    @DisplayName("review finding 2: part of the order is closed after a delivery - the next delivery makes the fee whole")
    void aClosureBetweenDeliveriesStillAddsUpToTheFee() {
        // Fee 100,000, order of 100 pieces. Shipment 1 carries 40: 40,000.
        BigDecimal first = share("100000", 100, 40, Invoiced.NOTHING);
        assertThat(first).isEqualByComparingTo("40000");
        // 20 pieces are closed: the order now means to deliver 80. Shipment 2 carries the other 40 and completes it:
        // it takes what is left of the fee, 60,000 - not 50,000 of a fee measured against a smaller order.
        BigDecimal second = share("100000", 80, 40, invoiced(40, "40000"));
        assertThat(second).isEqualByComparingTo("60000");
        assertThat(first.add(second)).isEqualByComparingTo("100000");

        // Closed further while a delivery is still to come: 30 of 100 delivered (30,000), then the order shrinks to
        // 60; the next 20 bring it to 50 of 60 -> 83,333 owed, 53,333 on this invoice; the last 10 take 16,667.
        BigDecimal middle = share("100000", 60, 20, invoiced(30, "30000"));
        BigDecimal last = share("100000", 60, 10, invoiced(50, "83333"));
        assertThat(middle).isEqualByComparingTo("53333");
        assertThat(last).isEqualByComparingTo("16667");
        assertThat(new BigDecimal("30000").add(middle).add(last)).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("the delivery that completes the order takes exactly what is left, never more than the fee")
    void theLastDeliveryTakesTheRemainder() {
        assertThat(share("100000", 100, 10, invoiced(90, "90000"))).isEqualByComparingTo("10000");
        assertThat(share("100000", 100, 10, invoiced(90, "89999"))).as("an earlier rounding is made up here")
                .isEqualByComparingTo("10001");
        assertThat(share("100000", 100, 10, invoiced(90, "100000"))).as("the fee is all invoiced").isEqualByComparingTo("0");
        // Deliveries past what the order still means to deliver (pieces closed after they left): still the fee.
        assertThat(share("100000", 80, 50, invoiced(50, "62500"))).isEqualByComparingTo("37500");
    }

    @Test
    @DisplayName("never below zero: an original takes nothing back when earlier invoices carry more than is owed now")
    void neverBelowZero() {
        // The order's invoices carry 60,000 for 20 pieces (the rest of what they invoiced was voided since): 30 of
        // 100 pieces with this delivery owe 30,000, which is less. Nothing is taken back on an original.
        assertThat(share("100000", 100, 10, invoiced(20, "60000"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("no fee, no share; nothing to measure by, the first invoice takes the fee")
    void edges() {
        assertThat(FeeAllocation.shippingShare(null, 100, 100, Invoiced.NOTHING)).isEqualByComparingTo("0");
        assertThat(share("0", 100, 100, Invoiced.NOTHING)).isEqualByComparingTo("0");
        assertThat(share("100000", 0, 10, Invoiced.NOTHING)).isEqualByComparingTo("100000");
        assertThat(share("100000", 0, 10, invoiced(10, "100000"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a voided draft drawn up again gets the share that is missing, whatever was invoiced meanwhile")
    void drawnUpAgainAfterAVoid() {
        // Shipments of 100, 100, 100 of 300. The first's draft was voided after the second was invoiced (33,334 as
        // the second of two), and is drawn up again: 200 of 300 invoiced with it -> 66,667 owed, 33,333 here.
        assertThat(share("100000", 300, 100, invoiced(100, "33334"))).isEqualByComparingTo("33333");
    }
}
