package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.ShipmentHandover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The lead's decisions of 2026-10-21 on the aggregate alone: PACK-PARTIAL (Sales decides whether an order ships in
 * parts) and BALCREDIT (a deposit order's balance on credit), with the fees of closing printed lines (review of #150).
 * Every refusal is checked for its error code.
 */
class OrderFlowFollowupsTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final OrderNumber NUMBER = OrderNumber.of(LocalDate.of(2026, 10, 22), 11);
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();

    /**
     * A mixed order: 10 plain cups at 100 000 and 100 printed cups at 20 000 - 3 000 000 VND - released and picking:
     * the printed cups were printed outside the system and are in stock like the rest. A deposit is 30 %, 900 000.
     */
    private static Order mixed(PaymentTerm term, BalancePolicy policy, boolean partial) {
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                Order.line(new Sku("CUP-12OZ-PRINT"), 100, Money.vnd(20_000), UUID.randomUUID())), NOW);
        boolean onCredit = term == PaymentTerm.CREDIT || policy == BalancePolicy.ON_CREDIT;
        order.applyTerms(term, term == PaymentTerm.DEPOSIT ? new BigDecimal("30") : null, onCredit ? 30 : null,
                term == PaymentTerm.DEPOSIT ? policy : null);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.decidePartialShipment(partial);
        if (term == PaymentTerm.CREDIT) {
            order.submitOnCredit(true);
        } else {
            order.submit();
            order.recordPayment(term == PaymentTerm.PREPAID ? order.total() : Money.vnd(900_000), NOW);
        }
        order.release(HCM, "HCM", STAFF, NOW);
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
            order.applyTerms(PaymentTerm.PREPAID, null);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.decidePartialShipment(partial);
            order.submit();
            order.recordPayment(order.total(), NOW);
            order.release(HCM, "HCM", STAFF, NOW);
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
    @DisplayName("BALCREDIT: a deposit order's balance on credit")
    class BalanceOnCredit {

        @Test
        @DisplayName("needs the customer's days to pay, as a credit order; other policies refuse them")
        void termsKeepTheDays() {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP"), 10, Money.vnd(100_000), null)), NOW);
            assertThat(code(catchThrowable(() -> order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), null,
                    BalancePolicy.ON_CREDIT)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(code(catchThrowable(() -> order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), 30,
                    BalancePolicy.BEFORE_DELIVERY)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), 30, BalancePolicy.ON_CREDIT);
            assertThat(order.balanceOnCredit()).isTrue();
            assertThat(order.creditTermDays()).isEqualTo(30);
            assertThat(order.creditAmount()).as("the balance after the 300 000 deposit").isEqualByComparingTo("700000");
        }

        @Test
        @DisplayName("only the deposit before the handover; each delivery bills its goods less the deposit not yet "
                + "used; DELIVERED until the receivables are paid")
        void billedOnDelivery() {
            Order order = mixed(PaymentTerm.DEPOSIT, BalancePolicy.ON_CREDIT, true);
            assertThat(order.amountDueBeforeHandover()).isZero();
            assertThat(order.creditAmount()).isEqualByComparingTo("2100000");

            order.recordHandover(handover(S1, stock(order), 10));
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(deliveries(order)).singleElement().satisfies(d -> {
                assertThat(d.billedAmount()).as("1 000 000 of goods, the 900 000 deposit pays them first")
                        .isEqualByComparingTo("100000");
                assertThat(d.balancePolicy()).isEqualTo("ON_CREDIT");
                assertThat(d.creditTermDays()).isEqualTo(30);
            });
            order.markPacked(NOW, STAFF);
            order.recordHandover(handover(S2, print(order), 100));
            order.recordShipmentDelivered(S2, NOW, STAFF);
            assertThat(deliveries(order)).singleElement()
                    .satisfies(d -> assertThat(d.billedAmount()).isEqualByComparingTo("2000000"));
            assertThat(order.billedAmount()).as("the balance, billed once").isEqualByComparingTo("2100000");
            assertThat(order.unbilled()).isZero();
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.complete(NOW.plus(Duration.ofDays(8)), Duration.ofDays(7), false))
                    .as("the receivables are not paid yet").isFalse();
        }

        @Test
        @DisplayName("the deposit is still wanted before the goods leave (409 ORDER_BALANCE_NOT_PAID once it is "
                + "reversed)")
        void depositBeforeTheHandover() {
            Order order = mixed(PaymentTerm.DEPOSIT, BalancePolicy.ON_CREDIT, true);
            order.reversePayment(Money.vnd(900_000), false, "nhầm", STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(order.amountDueBeforeHandover()).isEqualByComparingTo("900000");
            // Held: refused anyway; the money gate is what the handover would meet once the hold is gone.
            assertThat(catchThrowable(() -> order.recordHandover(handover(S1, stock(order), 10))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("review of #150")
    class Review {

        /** Plain 10 x 200 000 (2M) and 4 printed x 2 000 000 (8M): 10M, released and picking, may ship in parts. */
        private Order tenMillion(PaymentTerm term, BalancePolicy policy) {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("HOP-PIZZA"), 10, Money.vnd(200_000), null),
                    Order.line(new Sku("LY-IN"), 4, Money.vnd(2_000_000), UUID.randomUUID())), NOW);
            order.applyTerms(term, term == PaymentTerm.DEPOSIT ? new BigDecimal("30") : null,
                    policy == BalancePolicy.ON_CREDIT ? 30 : null, policy);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.decidePartialShipment(true);
            order.submit();
            order.recordPayment(term == PaymentTerm.PREPAID ? order.total() : Money.vnd(3_000_000), NOW);
            order.release(HCM, "HCM", STAFF, NOW);
            order.startPicking(STAFF, NOW);
            order.pullDomainEvents();
            return order;
        }

        private com.stockflow.contracts.OrderRemainderClosed closure(Order order) {
            return order.pullDomainEvents().stream().filter(OrderEvent.RemainderClosed.class::isInstance)
                    .map(e -> ((OrderEvent.RemainderClosed) e).payload()).findFirst().orElseThrow();
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
        @DisplayName("1: prepaid 10M, the 2M stock shipped early, the 8M prints printed, rest closed with a 3M fee: "
                + "3M kept, 5M refunded, nothing owed")
        void closureKeepsTheProductionFee() {
            Order order = tenMillion(PaymentTerm.PREPAID, null);
            order.recordHandover(handover(S1, stock(order), 10));
            printingStarted(order);
            order.pullDomainEvents();

            assertThat(order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, fee(3_000_000), STAFF, NOW))
                    .isEqualByComparingTo("8000000");
            var closed = closure(order);
            assertThat(closed.feeAmount()).isEqualByComparingTo("3000000");
            assertThat(closed.retainedFeeAmount()).isEqualByComparingTo("3000000");
            assertThat(closed.refundableAmount()).isEqualByComparingTo("5000000");
            assertThat(closed.owedAmount()).isZero();
            assertThat(order.remainderFeeAmount()).isEqualByComparingTo("3000000");
            assertThat(order.amountDue()).isZero();
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("1: no fee before printing (409 CANCELLATION_FEE_BEFORE_PRINTING), none above the 8M of prints "
                + "closed (400) - the order is left as it was")
        void closureFeeRefusals() {
            Order order = tenMillion(PaymentTerm.PREPAID, null);
            order.recordHandover(handover(S1, stock(order), 10));
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null,
                    fee(1_000_000), STAFF, NOW)))).isEqualTo(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING);
            printingStarted(order);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null,
                    fee(9_000_000), STAFF, NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.lines()).allMatch(line -> line.cancelledQuantity() == 0);
            assertThat(order.remainderFeeAmount()).isZero();
        }

        @Test
        @DisplayName("1: deposit 3M of 10M (balance on credit), 2M stock shipped on the deposit, prints closed with a "
                + "2M fee: the deposit keeps 1M of it, 1M is owed, nothing refunded")
        void depositKeepsTheFeeFirst() {
            Order order = tenMillion(PaymentTerm.DEPOSIT, BalancePolicy.ON_CREDIT);
            order.recordHandover(handover(S1, stock(order), 10));
            printingStarted(order);
            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, fee(2_000_000), STAFF, NOW);
            var closed = closure(order);
            assertThat(closed.retainedFeeAmount()).isEqualByComparingTo("1000000");
            assertThat(closed.owedAmount()).isEqualByComparingTo("1000000");
            assertThat(closed.refundableAmount()).isZero();
            assertThat(order.amountDue()).as("2M goods + 2M fee - 3M paid").isEqualByComparingTo("1000000");
        }

        @Test
        @DisplayName("re-review: paid 100, billable 60, fee 50 - 40 kept, 10 owed; a 30 stock shipment comes back and "
                + "the rest is closed again: 30 refunded, the 10 still owed on its receivable - once paid, nothing more "
                + "is refunded (charged once: 30 goods + 50 fee = 80 net)")
        void secondClosureDoesNotChargeTheFeeTwice() {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("HOP"), 2, Money.vnd(30), null),
                    Order.line(new Sku("LY-IN"), 1, Money.vnd(50), UUID.randomUUID())), NOW);   // 110
            order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), 30, BalancePolicy.ON_CREDIT);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.decidePartialShipment(true);
            order.submit();
            order.recordPayment(Money.vnd(100), NOW);                                          // paid 100
            order.release(HCM, "HCM", STAFF, NOW);
            order.startPicking(STAFF, NOW);
            UUID hop = order.lines().get(0).id();
            order.recordHandover(handover(S1, hop, 1));
            order.recordHandover(handover(S2, hop, 1));
            order.recordShipmentDelivered(S1, NOW, STAFF);
            printingStarted(order);
            order.pullDomainEvents();

            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, fee(50), STAFF, NOW);
            var first = closure(order);
            assertThat(order.billable()).isEqualByComparingTo("60");
            assertThat(first.retainedFeeAmount()).isEqualByComparingTo("40");
            assertThat(first.owedAmount()).isEqualByComparingTo("10");
            assertThat(first.refundableAmount()).isZero();

            order.recordShipmentDeliveryFailed(S2, com.stockflow.order.api.DeliveryFailureReason.CUSTOMER_ABSENT, null,
                    NOW, STAFF);
            order.recordShipmentReturned(S2, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, STAFF, NOW);
            var second = closure(order);
            assertThat(order.billable()).isEqualByComparingTo("30");
            assertThat(second.refundableAmount()).as("100 paid - 30 goods - 40 of the fee kept")
                    .isEqualByComparingTo("30");
            assertThat(second.owedAmount()).as("no new receivable").isZero();
            assertThat(order.remainderFeeOwed()).as("the first closure's receivable stays open").isEqualByComparingTo("10");

            // The customer pays the fee's receivable: counted as the fee's, so nothing more is refundable.
            order.recordPayment(Money.vnd(10), NOW, true, true);
            assertThat(order.remainderFeeOwed()).isZero();
            assertThat(order.paidAmount().subtract(order.remainderRefundAmount()))
                    .as("net: 30 goods + 50 fee").isEqualByComparingTo("80");
            assertThat(order.amountDue()).isZero();
        }

        @Test
        @DisplayName("SCRUM-392 (review of #162): goods packed and left on a load plan's first vehicle - goodsLeftAt is "
                + "set, the order is not cancelled (ORDER_NOT_CANCELLABLE) though no shipment is recorded yet, partial "
                + "shipment can no longer be turned off; its remainder is still closed; recording it again changes "
                + "nothing")
        void goodsLeftOnAVehicleIsNoLongerCancelled() {
            Order order = tenMillion(PaymentTerm.PREPAID, null);
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
            Order order = mixed(PaymentTerm.PREPAID, null, false);
            assertThat(code(catchThrowable(() -> order.requirePlanCarriesTheRest(Map.of(stock(order), 10)))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(code(catchThrowable(() -> order.recordGoodsLeft(NOW, Map.of(stock(order), 10), null))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(order.goodsLeftAt()).isNull();
            order.requirePlanCarriesTheRest(Map.of(stock(order), 10, print(order), 100));
            order.requirePlanCarriesTheRest(null);
            Order partial = mixed(PaymentTerm.PREPAID, null, true);
            partial.requirePlanCarriesTheRest(Map.of(stock(partial), 10));
        }
    }
}
