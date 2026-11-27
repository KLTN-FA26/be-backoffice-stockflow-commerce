package com.stockflow.payment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-05 Q-19, as the owner decided on 2026-11-23 ({@link OrderSettlement#shippingFeeShare}): a shipment the carrier
 * brought back or lost waives the order's shipping fee in proportion to its goods' share of the order's goods - one of
 * three, a third; all of them, the whole fee; none, nothing. Worked out cumulatively and rounded half up, so the
 * shipments' waivers add up to the fee to the dong whatever the order they come back in. And the Q-19 edge, decided by
 * the owner later that day: an order that ends with nothing delivered after a carrier return waives the whole fee, even
 * with part of it closed before it shipped - with whichever settlement ends it, exactly once.
 *
 * <p>The order of every scenario: three shipments of goods worth 700,000 each with VAT (2,100,000), and a shipping fee
 * of 100,000 with VAT - a third of it is 33,333.33..., which does not round evenly.</p>
 */
class ShippingFeeShareTest {

    private static final Instant AT = Instant.parse("2026-11-23T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 23);
    private static final BigDecimal FEE = dong(100_000);
    private static final BigDecimal ORDER_GOODS = dong(2_100_000);

    private final UUID orderId = UUID.randomUUID();
    private final List<Installment> plan = List.of(Installment.open(UUID.randomUUID(), UUID.randomUUID(), orderId,
            UUID.randomUUID(), "HD-1", "SO-1", 1, "After delivery", InstallmentMilestone.AFTER_DELIVERY, null, null,
            dong(2_200_000), "VND", 30));

    private static BigDecimal dong(long amount) {
        return BigDecimal.valueOf(amount);
    }

    /** One shipment brought back: its share worked out against the settlements before it, then settled. */
    private OrderSettlement bringBack(List<OrderSettlement> earlier, long goods) {
        BigDecimal waived = OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, false, earlier, dong(goods));
        OrderSettlement settled = OrderSettlement.shipmentReturned(plan, UUID.randomUUID(), UUID.randomUUID(), null,
                dong(goods), waived, null, BigDecimal.ZERO, null, AT, DAY).settlement();
        earlier.add(settled);
        return settled;
    }

    /** The same, the caller saying whether the order then ends with nothing delivered (the Q-19 edge). */
    private OrderSettlement bringBack(List<OrderSettlement> earlier, long goods, boolean endsWithNothingDelivered) {
        BigDecimal waived = OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, endsWithNothingDelivered, earlier,
                dong(goods));
        OrderSettlement settled = OrderSettlement.shipmentReturned(plan, UUID.randomUUID(), UUID.randomUUID(), null,
                dong(goods), waived, null, BigDecimal.ZERO, null, AT, DAY).settlement();
        earlier.add(settled);
        return settled;
    }

    /** The rest of the order closed - goods never shipped - its waiver worked out by the same rule, then settled. */
    private OrderSettlement closeRest(List<OrderSettlement> earlier, long goods, boolean endsWithNothingDelivered) {
        BigDecimal waived = OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, endsWithNothingDelivered, earlier, null);
        OrderSettlement settled = OrderSettlement.closeRemainder(plan, UUID.randomUUID(), dong(goods),
                OrderSettlement.Fee.none(), null, AT, DAY, null, waived).settlement();
        earlier.add(settled);
        return settled;
    }

    private static BigDecimal waivedBy(List<OrderSettlement> settled) {
        return settled.stream().map(OrderSettlement::shippingFeeWaived).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("1 of 3 shipments brought back: a third of the fee, rounded half up to the dong; the two delivered "
            + "keep theirs")
    void oneOfThree() {
        List<OrderSettlement> settled = new ArrayList<>();

        OrderSettlement first = bringBack(settled, 700_000);

        assertThat(first.shippingFeeWaived()).isEqualByComparingTo(dong(33_333));
        assertThat(first.closed()).as("the goods and the fee waived with them").isEqualByComparingTo(dong(733_333));
    }

    @Test
    @DisplayName("all 3 brought back: the waivers add up to the whole fee - the rounding goes to the shipment it falls on, "
            + "never accumulates")
    void allThree() {
        List<OrderSettlement> settled = new ArrayList<>();

        assertThat(bringBack(settled, 700_000).shippingFeeWaived()).isEqualByComparingTo(dong(33_333));
        assertThat(bringBack(settled, 700_000).shippingFeeWaived()).as("66,667 through two, less 33,333")
                .isEqualByComparingTo(dong(33_334));
        assertThat(bringBack(settled, 700_000).shippingFeeWaived()).as("the last takes what is left")
                .isEqualByComparingTo(dong(33_333));
        assertThat(waivedBy(settled)).isEqualByComparingTo(FEE);
    }

    @Test
    @DisplayName("none brought back waives nothing; neither does an order without a shipping fee, or goods worth nothing")
    void none() {
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, false, List.of(), BigDecimal.ZERO)).isZero();
        assertThat(OrderSettlement.shippingFeeShare(BigDecimal.ZERO, BigDecimal.ZERO, true, List.of(), dong(700_000)))
                .isZero();
        assertThat(OrderSettlement.shippingFeeShare(null, null, false, List.of(), dong(700_000))).isZero();
    }

    @Test
    @DisplayName("shipments of unequal value waive by value, not by count; whatever the order they come back in, all of "
            + "them waive the whole fee")
    void byValue() {
        List<OrderSettlement> settled = new ArrayList<>();
        BigDecimal fee = dong(100_001);
        BigDecimal orderGoods = dong(2_100_000);

        BigDecimal small = OrderSettlement.shippingFeeShare(fee, orderGoods, false, settled, dong(300_000));
        assertThat(small).as("100,001 x 3/21 = 14,285.857...").isEqualByComparingTo(dong(14_286));
        settled.add(OrderSettlement.shipmentReturned(plan, UUID.randomUUID(), UUID.randomUUID(), null, dong(300_000),
                small, null, BigDecimal.ZERO, null, AT, DAY).settlement());
        BigDecimal large = OrderSettlement.shippingFeeShare(fee, orderGoods, false, settled, dong(1_200_000));
        assertThat(large).as("100,001 x 15/21 = 71,429.29 through both, less 14,286").isEqualByComparingTo(
                dong(57_143));
        settled.add(OrderSettlement.shipmentReturned(plan, UUID.randomUUID(), UUID.randomUUID(), null,
                dong(1_200_000), large, null, BigDecimal.ZERO, null, AT, DAY).settlement());
        BigDecimal last = OrderSettlement.shippingFeeShare(fee, orderGoods, false, settled, dong(600_000));
        assertThat(small.add(large).add(last)).isEqualByComparingTo(fee);
    }

    @Test
    @DisplayName("never more than the fee: goods said to be worth more than the order's waive only what is left; other "
            + "kinds of settlement neither count goods nor waive")
    void bounded() {
        List<OrderSettlement> settled = new ArrayList<>();
        bringBack(settled, 700_000);
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, false, settled, dong(5_000_000)))
                .isEqualByComparingTo(dong(66_667));
        assertThatThrownBy(() -> OrderSettlement.shippingFeeShare(FEE, BigDecimal.ZERO, false, settled, dong(1)))
                .as("a fee charged for no goods is a caller's error").isInstanceOf(IllegalArgumentException.class);

        List<Installment> paid = List.of(Installment.open(UUID.randomUUID(), UUID.randomUUID(), orderId,
                UUID.randomUUID(), "HD-1", "SO-1", 1, "Deposit", InstallmentMilestone.ON_SIGNING, null, null,
                dong(2_200_000), "VND", 0));
        paid.get(0).reach(AT.minusSeconds(86_400), DAY.minusDays(1));
        paid.get(0).pay(dong(2_200_000), AT.minusSeconds(86_400));
        OrderSettlement customerReturn = OrderSettlement.returned(paid, UUID.randomUUID(), null, dong(700_000),
                java.util.Set.of(), BigDecimal.ZERO, null, AT, DAY).settlement();
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, false, List.of(customerReturn), dong(700_000)))
                .as("a customer's return of delivered goods is no failed trip").isEqualByComparingTo(dong(33_333));
    }

    // ---- the Q-19 edge (owner 2026-11-23): nothing delivered, the whole fee ----------------------------------------

    @Test
    @DisplayName("Q-19 edge: two thirds closed before they shipped, the third shipped and brought back, nothing "
            + "delivered - the whole fee, not the third its goods are worth")
    void nothingDeliveredRestClosedBeforeShipping() {
        List<OrderSettlement> settled = new ArrayList<>();

        OrderSettlement closure = closeRest(settled, 1_400_000, false);
        assertThat(closure.shippingFeeWaived()).as("the shipment was still on its way: the closing rule").isZero();
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, false, settled, dong(700_000)))
                .as("were something delivered, or still coming: the share").isEqualByComparingTo(dong(33_333));
        OrderSettlement back = bringBack(settled, 700_000, true);

        assertThat(back.shippingFeeWaived()).isEqualByComparingTo(FEE);
        assertThat(back.closed()).as("the goods and the whole fee").isEqualByComparingTo(dong(800_000));
        assertThat(waivedBy(settled)).isEqualByComparingTo(FEE);
    }

    @Test
    @DisplayName("Q-19 edge: two shipments brought back, each its share while the rest was still to ship; the closure "
            + "of the rest that ends the order with nothing delivered waives what they left - the whole fee, exactly, "
            + "once")
    void nothingDeliveredAfterTwoPartialReturns() {
        List<OrderSettlement> settled = new ArrayList<>();

        assertThat(bringBack(settled, 700_000, false).shippingFeeWaived()).isEqualByComparingTo(dong(33_333));
        assertThat(bringBack(settled, 700_000, false).shippingFeeWaived()).isEqualByComparingTo(dong(33_334));
        OrderSettlement closure = closeRest(settled, 700_000, true);

        assertThat(closure.shippingFeeWaived()).as("what the two returns left").isEqualByComparingTo(dong(33_333));
        assertThat(closure.closed()).as("the goods closed and the fee waived with them")
                .isEqualByComparingTo(dong(733_333));
        assertThat(waivedBy(settled)).isEqualByComparingTo(FEE);
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, true, settled, dong(700_000)))
                .as("anything settled after: nothing more, never above the fee").isZero();
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, true, settled, null)).isZero();
    }

    @Test
    @DisplayName("Q-19 edge: one shipment brought back, the rest closed while a second was on its way, the second "
            + "brought back last - it ends the order with nothing delivered and takes what is left")
    void nothingDeliveredEndedByTheLastShipment() {
        List<OrderSettlement> settled = new ArrayList<>();

        assertThat(bringBack(settled, 700_000, false).shippingFeeWaived()).isEqualByComparingTo(dong(33_333));
        assertThat(closeRest(settled, 700_000, false).shippingFeeWaived()).as("the second still on its way")
                .isZero();
        assertThat(bringBack(settled, 700_000, true).shippingFeeWaived()).isEqualByComparingTo(dong(66_667));
        assertThat(waivedBy(settled)).isEqualByComparingTo(FEE);
    }

    @Test
    @DisplayName("at least one shipment delivered: the share stays - the shipment brought back a third, the closure of "
            + "the rest nothing (its part follows the closing rule)")
    void oneDeliveredStaysProRata() {
        List<OrderSettlement> settled = new ArrayList<>();
        // The first shipment was delivered: it settles nothing.

        assertThat(bringBack(settled, 700_000, false).shippingFeeWaived()).isEqualByComparingTo(dong(33_333));
        assertThat(closeRest(settled, 700_000, false).shippingFeeWaived()).isZero();
        assertThat(waivedBy(settled)).isEqualByComparingTo(dong(33_333));
    }

    @Test
    @DisplayName("a closure with nothing delivered but no carrier return before it - shipments cancelled before they "
            + "left - waives nothing: no trip failed")
    void noCarrierReturnNoWaiver() {
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, true, List.of(), null)).isZero();
        List<OrderSettlement> settled = new ArrayList<>();
        closeRest(settled, 700_000, false);
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, true, settled, null)).isZero();
    }
}
