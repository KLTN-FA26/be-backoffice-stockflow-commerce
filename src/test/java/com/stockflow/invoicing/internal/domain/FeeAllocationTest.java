package com.stockflow.invoicing.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order's fee lines over the invoices of its deliveries (plan §1.4): plate fees on the first, the shipping fee by
 * the pieces each delivery carried - adding up to the fee exactly, whatever the rounding.
 */
class FeeAllocationTest {

    private static BigDecimal share(String fee, long orderUnits, long before, long inShipment) {
        return FeeAllocation.shippingShare(new BigDecimal(fee), orderUnits, before, inShipment);
    }

    @Test
    @DisplayName("only the order's first delivery carries the plate fees")
    void plateFeesOnTheFirstDelivery() {
        assertThat(FeeAllocation.carriesPlateFees(0)).isTrue();
        assertThat(FeeAllocation.carriesPlateFees(1)).isFalse();
        assertThat(FeeAllocation.carriesPlateFees(5_000)).isFalse();
    }

    @Test
    @DisplayName("one delivery of everything carries the whole shipping fee")
    void oneDeliveryTakesTheWholeFee() {
        assertThat(share("500000", 1000, 0, 1000)).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("deliveries share the fee by pieces, and the shares add up to the fee to the dong")
    void sharesAddUpExactly() {
        // 100,000 over three equal deliveries: 33,333 + 33,334 + 33,333 - the running total is rounded, not each share.
        BigDecimal first = share("100000", 300, 0, 100);
        BigDecimal second = share("100000", 300, 100, 100);
        BigDecimal third = share("100000", 300, 200, 100);
        assertThat(first).isEqualByComparingTo("33333");
        assertThat(second).isEqualByComparingTo("33334");
        assertThat(third).isEqualByComparingTo("33333");
        assertThat(first.add(second).add(third)).isEqualByComparingTo("100000");

        // Uneven deliveries of an awkward fee.
        BigDecimal a = share("999999", 700, 0, 150);
        BigDecimal b = share("999999", 700, 150, 449);
        BigDecimal c = share("999999", 700, 599, 101);
        assertThat(a.add(b).add(c)).isEqualByComparingTo("999999");
        assertThat(a).isEqualByComparingTo("214286");
    }

    @Test
    @DisplayName("a shipment's share depends only on where it stands among the deliveries: asked again, the same")
    void theSameShipmentTheSameShare() {
        assertThat(share("100000", 300, 100, 100)).isEqualByComparingTo(share("100000", 300, 100, 100));
    }

    @Test
    @DisplayName("no fee, no share; a delivery past what the order still means to deliver never goes past the fee")
    void edges() {
        assertThat(FeeAllocation.shippingShare(null, 100, 0, 100)).isEqualByComparingTo("0");
        assertThat(share("0", 100, 0, 100)).isEqualByComparingTo("0");
        // Part of the order was closed after 80 of 100 pieces had left: the order now means to deliver 80.
        assertThat(share("100000", 80, 0, 50)).isEqualByComparingTo("62500");
        assertThat(share("100000", 80, 50, 30)).isEqualByComparingTo("37500");
        assertThat(share("100000", 80, 80, 20)).as("everything owed was invoiced already").isEqualByComparingTo("0");
        // Nothing to measure by: the first delivery takes it, as a single delivery would.
        assertThat(share("100000", 0, 0, 10)).isEqualByComparingTo("100000");
        assertThat(share("100000", 0, 10, 10)).isEqualByComparingTo("0");
    }
}
