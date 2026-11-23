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
 * shipments' waivers add up to the fee to the dong whatever the order they come back in.
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
        BigDecimal waived = OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, earlier, dong(goods));
        OrderSettlement settled = OrderSettlement.shipmentReturned(plan, UUID.randomUUID(), UUID.randomUUID(), null,
                dong(goods), waived, null, BigDecimal.ZERO, null, AT, DAY).settlement();
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
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, List.of(), BigDecimal.ZERO)).isZero();
        assertThat(OrderSettlement.shippingFeeShare(BigDecimal.ZERO, BigDecimal.ZERO, List.of(), dong(700_000)))
                .isZero();
        assertThat(OrderSettlement.shippingFeeShare(null, null, List.of(), dong(700_000))).isZero();
    }

    @Test
    @DisplayName("shipments of unequal value waive by value, not by count; whatever the order they come back in, all of "
            + "them waive the whole fee")
    void byValue() {
        List<OrderSettlement> settled = new ArrayList<>();
        BigDecimal fee = dong(100_001);
        BigDecimal orderGoods = dong(2_100_000);

        BigDecimal small = OrderSettlement.shippingFeeShare(fee, orderGoods, settled, dong(300_000));
        assertThat(small).as("100,001 x 3/21 = 14,285.857...").isEqualByComparingTo(dong(14_286));
        settled.add(OrderSettlement.shipmentReturned(plan, UUID.randomUUID(), UUID.randomUUID(), null, dong(300_000),
                small, null, BigDecimal.ZERO, null, AT, DAY).settlement());
        BigDecimal large = OrderSettlement.shippingFeeShare(fee, orderGoods, settled, dong(1_200_000));
        assertThat(large).as("100,001 x 15/21 = 71,429.29 through both, less 14,286").isEqualByComparingTo(
                dong(57_143));
        settled.add(OrderSettlement.shipmentReturned(plan, UUID.randomUUID(), UUID.randomUUID(), null,
                dong(1_200_000), large, null, BigDecimal.ZERO, null, AT, DAY).settlement());
        BigDecimal last = OrderSettlement.shippingFeeShare(fee, orderGoods, settled, dong(600_000));
        assertThat(small.add(large).add(last)).isEqualByComparingTo(fee);
    }

    @Test
    @DisplayName("never more than the fee: goods said to be worth more than the order's waive only what is left; other "
            + "kinds of settlement neither count goods nor waive")
    void bounded() {
        List<OrderSettlement> settled = new ArrayList<>();
        bringBack(settled, 700_000);
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, settled, dong(5_000_000)))
                .isEqualByComparingTo(dong(66_667));
        assertThatThrownBy(() -> OrderSettlement.shippingFeeShare(FEE, BigDecimal.ZERO, settled, dong(1)))
                .as("a fee charged for no goods is a caller's error").isInstanceOf(IllegalArgumentException.class);

        List<Installment> paid = List.of(Installment.open(UUID.randomUUID(), UUID.randomUUID(), orderId,
                UUID.randomUUID(), "HD-1", "SO-1", 1, "Deposit", InstallmentMilestone.ON_SIGNING, null, null,
                dong(2_200_000), "VND", 0));
        paid.get(0).reach(AT.minusSeconds(86_400), DAY.minusDays(1));
        paid.get(0).pay(dong(2_200_000), AT.minusSeconds(86_400));
        OrderSettlement customerReturn = OrderSettlement.returned(paid, UUID.randomUUID(), null, dong(700_000),
                java.util.Set.of(), BigDecimal.ZERO, null, AT, DAY).settlement();
        assertThat(OrderSettlement.shippingFeeShare(FEE, ORDER_GOODS, List.of(customerReturn), dong(700_000)))
                .as("a customer's return of delivered goods is no failed trip").isEqualByComparingTo(dong(33_333));
    }
}
