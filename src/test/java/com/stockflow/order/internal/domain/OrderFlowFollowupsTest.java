package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShipmentHandover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The lead's decisions of 2026-10-21 on the aggregate alone: PACK-PARTIAL (Sales decides whether an order ships in
 * parts), with the fees of closing printed lines (review of #150). Every refusal is checked for its error code. BALCREDIT
 * (a deposit order's balance on credit) went with the order's payment terms (4B-10): what a closure keeps, refunds or is
 * owed is the contract's installments' now, so the closure's money fields are not asserted here.
 */
class OrderFlowFollowupsTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final OrderNumber NUMBER = OrderNumber.of(LocalDate.of(2026, 10, 22), 11);
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();

    /** Its contract signed with nothing due on signing - CONFIRMED - shipping from HCM and released there. */
    private static void confirmAndRelease(Order order) {
        order.sendContract(STAFF, NOW);
        order.contractSigned(false, false, STAFF, NOW);
        order.chooseShippingWarehouse(HCM, true, STAFF, NOW);
        order.release(HCM, "HCM", STAFF, NOW);
    }

    /**
     * A mixed order: 10 plain cups at 100 000 and 100 printed cups at 20 000 - 3 000 000 VND - released and picking:
     * the printed cups were printed outside the system and are in stock like the rest.
     */
    private static Order mixed(boolean partial) {
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                Order.line(new Sku("CUP-12OZ-PRINT"), 100, Money.vnd(20_000), UUID.randomUUID())), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.decidePartialShipment(partial);
        confirmAndRelease(order);
        order.startPicking(STAFF, NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    private static UUID stock(Order order) {
        return order.lines().get(0).id();
    }

    private static UUID print(Order order) {
        return order.lines().get(1).id();
    }

    private static ShipmentHandover handover(UUID shipment, UUID line, int quantity) {
        return new ShipmentHandover(shipment, List.of(new ShipmentHandover.Line(line, quantity)), NOW, STAFF);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static List<OrderDelivered> deliveries(Order order) {
        return order.pullDomainEvents().stream().filter(OrderEvent.Delivered.class::isInstance)
                .map(e -> ((OrderEvent.Delivered) e).payload()).toList();
    }

    @Nested
    @DisplayName("PACK-PARTIAL: Sales decides whether the order ships in parts")
    class PackPartial {

        private Order packedStockOrder(boolean partial) {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                    Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null)), NOW);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.decidePartialShipment(partial);
            confirmAndRelease(order);
            order.startPicking(STAFF, NOW);
            order.markPacked(NOW, STAFF);
            return order;
        }

        @Test
        @DisplayName("not allowed: part of it handed over is refused; everything in one signature - two shipments, the "
                + "first told the second leaves with it - is not")
        void wholeOrNothingWithoutTheFlag() {
            Order order = packedStockOrder(false);
            UUID cups = order.lines().get(0).id();
            UUID lids = order.lines().get(1).id();
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S1, cups, 10)))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(order.status()).isEqualTo(OrderStatus.PACKED);

            order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(cups, 10)), NOW, STAFF,
                    null, List.of(new ShipmentHandover.Line(lids, 5))));
            order.recordHandover(handover(S2, lids, 5));
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("allowed: part of it leaves (PARTIALLY_FULFILLED); the decision is idempotent and locked once "
                + "everything left (409 ORDER_PARTIAL_SHIPMENT_LOCKED)")
        void allowedThenLocked() {
            Order order = packedStockOrder(true);
            assertThat(order.decidePartialShipment(true)).as("the same decision again").isFalse();
            order.recordHandover(handover(S1, order.lines().get(0).id(), 10));
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.decidePartialShipment(false)).as("the rest goes whole").isTrue();
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S2, order.lines().get(1).id(), 4)))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            order.recordHandover(handover(S2, order.lines().get(1).id(), 5));
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(code(catchThrowable(() -> order.decidePartialShipment(true))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_LOCKED);
        }
    }

    @Nested
    @DisplayName("review of #150")
    class Review {

        /** Plain 10 x 200 000 (2M) and 4 printed x 2 000 000 (8M): 10M, released and picking, may ship in parts. */
        private Order tenMillion() {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("HOP-PIZZA"), 10, Money.vnd(200_000), null),
                    Order.line(new Sku("LY-IN"), 4, Money.vnd(2_000_000), UUID.randomUUID())), NOW);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.decidePartialShipment(true);
            confirmAndRelease(order);
            order.startPicking(STAFF, NOW);
            order.pullDomainEvents();
            return order;
        }

        /**
         * The Production Order records when printing started (17 BR-11); the order only reads it, as it reads it back
         * from its row.
         */
        private void printingStarted(Order order) {
            order.restoreFulfilmentFacts(NOW, order.packedAt());
        }

        private com.stockflow.order.api.CancellationFee fee(long amount) {
            return new com.stockflow.order.api.CancellationFee(BigDecimal.valueOf(amount), "Phí in đã thực hiện",
                    "4 ly đã in theo thiết kế, huỷ theo yêu cầu khách");
        }

        @Test
        @DisplayName("1: 10M, the 2M stock shipped early, the 8M prints printed, rest closed with a 3M fee: the 8M "
                + "closed (what the fee keeps is the installments', not the order's)")
        void closureKeepsTheProductionFee() {
            Order order = tenMillion();
            order.recordHandover(handover(S1, stock(order), 10));
            printingStarted(order);
            order.pullDomainEvents();

            assertThat(order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, fee(3_000_000), STAFF, NOW))
                    .isEqualByComparingTo("8000000");
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("1: no fee before printing (409 CANCELLATION_FEE_BEFORE_PRINTING), none above the 8M of prints "
                + "closed (400) - the order is left as it was")
        void closureFeeRefusals() {
            Order order = tenMillion();
            order.recordHandover(handover(S1, stock(order), 10));
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null,
                    fee(1_000_000), STAFF, NOW)))).isEqualTo(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING);
            printingStarted(order);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null,
                    fee(9_000_000), STAFF, NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.lines()).allMatch(line -> line.cancelledQuantity() == 0);
        }

        @Test
        @DisplayName("SCRUM-392 (review of #162): goods packed and left on a load plan's first vehicle - goodsLeftAt is "
                + "set, the order is not cancelled (ORDER_NOT_CANCELLABLE) though no shipment is recorded yet, partial "
                + "shipment can no longer be turned off; its remainder is still closed; recording it again changes "
                + "nothing")
        void goodsLeftOnAVehicleIsNoLongerCancelled() {
            Order order = tenMillion();
            order.recordPacked(NOW);
            printingStarted(order);
            assertThat(order.recordGoodsLeft(NOW, Map.of(stock(order), 10), null)).isTrue();
            assertThat(order.recordGoodsLeft(NOW.plusSeconds(60), Map.of(stock(order), 10), null)).isFalse();
            assertThat(order.goodsLeftAt()).isEqualTo(NOW);
            assertThat(order.packedForGood()).isTrue();
            assertThat(code(catchThrowable(order::requireNotPacked))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
            assertThat(code(catchThrowable(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                    fee(2_000_000), STAFF, NOW)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
            assertThat(code(catchThrowable(() -> order.decidePartialShipment(false))))
                    .as("the vehicles still to leave carry the rest").isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_LOCKED);
            assertThat(order.allowPartialShipment()).isTrue();
            // The last vehicle signs: the plain cups' shipment is recorded, the prints are what is left to close.
            order.recordHandover(handover(S1, stock(order), 10));
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, STAFF, NOW)).isPositive();
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("SCRUM-392 (review of #162): an order that may not ship in parts lets goods leave on a load plan "
                + "only when the plan carries every unit it still wants (409 ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED)")
        void aPlanCarriesTheRestWithoutTheFlag() {
            Order order = mixed(false);
            assertThat(code(catchThrowable(() -> order.requirePlanCarriesTheRest(Map.of(stock(order), 10)))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(code(catchThrowable(() -> order.recordGoodsLeft(NOW, Map.of(stock(order), 10), null))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(order.goodsLeftAt()).isNull();
            order.requirePlanCarriesTheRest(Map.of(stock(order), 10, print(order), 100));
            order.requirePlanCarriesTheRest(null);
            Order partial = mixed(true);
            partial.requirePlanCarriesTheRest(Map.of(stock(partial), 10));
        }
    }
}
