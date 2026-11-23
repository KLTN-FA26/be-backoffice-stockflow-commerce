package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.payment.api.SettleShipmentReturnCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 Q-19 (owner, 23/11), the order's side: what the order hands payment to waive the shipping fee by
 * ({@link OrderServiceImpl#shippingFeeOf}) - the shipping fee line with its VAT and every goods line with its VAT as
 * signed, never the plate fee. Payment works the share out; the arithmetic is pinned in {@code ShippingFeeShareTest}.
 */
class ShipmentClosureShippingFeeTest {

    private static final BigDecimal TEN = new BigDecimal("10");

    /** 1,000 cups at 2,000 and 100 lids at 500, 10% VAT; fees as given. */
    private static Order order(List<OrderFee> fees) {
        OrderLine cups = Order.line(UUID.randomUUID(), new Sku("LY12-ACME-01"), 1000, Money.vnd(2_000), null)
                .withTax(TEN);
        OrderLine lids = Order.line(UUID.randomUUID(), new Sku("NAP-90"), 100, Money.vnd(500), null).withTax(TEN);
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 23), 1), UUID.randomUUID(), UUID.randomUUID(),
                List.of(cups, lids), Instant.now());
        order.attachFees(fees);
        return order;
    }

    @Test
    @DisplayName("the shipping fee with its VAT against the goods with theirs; the plate fee is in neither")
    void feeAndGoods() {
        Order order = order(List.of(
                OrderFee.of(UUID.randomUUID(), FeeKind.PLATE_FEE, new Sku("LY12-ACME-01"), Money.vnd(500_000), TEN),
                OrderFee.of(UUID.randomUUID(), FeeKind.SHIPPING_FEE, null, Money.vnd(300_000), TEN)));

        SettleShipmentReturnCommand.ShippingFee fee = OrderServiceImpl.shippingFeeOf(order);

        assertThat(fee.fee()).as("300,000 + 10%").isEqualByComparingTo("330000");
        assertThat(fee.orderGoodsValue()).as("2,000,000 + 50,000, each + 10%").isEqualByComparingTo("2255000");
        assertThat(fee.endsWithNothingDelivered()).as("nothing shipped yet: not a whole return").isFalse();
    }

    @Test
    @DisplayName("no shipping fee line: nothing to waive (NONE), a plate fee or not")
    void noShippingFee() {
        assertThat(OrderServiceImpl.shippingFeeOf(order(List.of())))
                .isEqualTo(SettleShipmentReturnCommand.ShippingFee.NONE);
        assertThat(OrderServiceImpl.shippingFeeOf(order(List.of(OrderFee.of(UUID.randomUUID(), FeeKind.PLATE_FEE,
                new Sku("LY12-ACME-01"), Money.vnd(500_000), TEN)))))
                .isEqualTo(SettleShipmentReturnCommand.ShippingFee.NONE);
    }

    /** Released, split shipments allowed, picking: 10 cups and 5 lids (OrderFixtures). */
    private static Order picking() {
        Order order = OrderFixtures.released();
        order.decidePartialShipment(true);
        order.startPicking(OrderFixtures.STAFF, OrderFixtures.NOW);
        return order;
    }

    private static UUID handOver(Order order, int line, int units) {
        UUID shipment = UUID.randomUUID();
        order.recordHandover(new ShipmentHandover(shipment, List.of(new ShipmentHandover.Line(
                order.lines().get(line).id(), units)), OrderFixtures.NOW, OrderFixtures.STAFF));
        return shipment;
    }

    private static void broughtBack(Order order, UUID shipment) {
        order.recordShipmentDeliveryFailed(shipment, DeliveryFailureReason.REFUSED, null, OrderFixtures.NOW,
                OrderFixtures.STAFF);
        order.recordShipmentReturned(shipment, OrderFixtures.NOW, OrderFixtures.STAFF);
        order.closeReturnedShipment(shipment, OrderFixtures.NOW, OrderFixtures.NOW, OrderFixtures.STAFF);
    }

    @Test
    @DisplayName("Q-19 edge: the cups brought back, the lids still to ship - not over; the lids closed after - a whole "
            + "return, read after the closure")
    void returnThenRestClosed() {
        Order order = picking();
        broughtBack(order, handOver(order, 0, 10));
        assertThat(OrderServiceImpl.endsWithNothingDelivered(order)).as("the lids are still to come").isFalse();

        order.closeRemainder(com.stockflow.order.api.CancellationReasonCode.OUT_OF_STOCK, "no lids", null,
                java.util.Map.of(), OrderFixtures.STAFF, OrderFixtures.NOW);

        assertThat(OrderServiceImpl.endsWithNothingDelivered(order)).isTrue();
    }

    @Test
    @DisplayName("Q-19 edge: the lids closed while the cups are with the carrier - not over; the cups brought back "
            + "after - a whole return")
    void restClosedThenReturn() {
        Order order = picking();
        UUID cups = handOver(order, 0, 10);
        order.closeRemainder(com.stockflow.order.api.CancellationReasonCode.OUT_OF_STOCK, "no lids", null,
                java.util.Map.of(), OrderFixtures.STAFF, OrderFixtures.NOW);
        assertThat(OrderServiceImpl.endsWithNothingDelivered(order)).as("the cups are on their way").isFalse();

        broughtBack(order, cups);

        assertThat(OrderServiceImpl.endsWithNothingDelivered(order)).isTrue();
    }

    @Test
    @DisplayName("Q-19 edge: something delivered - never a whole return; the pro-rata share stays")
    void somethingDelivered() {
        Order order = picking();
        UUID cups = handOver(order, 0, 10);
        UUID lids = handOver(order, 1, 5);
        order.recordShipmentDelivered(cups, OrderFixtures.NOW, OrderFixtures.STAFF);
        broughtBack(order, lids);

        assertThat(order.deliveriesOver()).isTrue();
        assertThat(OrderServiceImpl.endsWithNothingDelivered(order)).isFalse();
    }
}
