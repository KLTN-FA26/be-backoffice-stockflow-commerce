package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
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
 * parts), PACK-EARLY (a mixed order's stock ships while its prints are made) and BALCREDIT (a deposit order's balance
 * on credit). Every refusal is checked for its error code.
 */
class OrderFlowFollowupsTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final OrderNumber NUMBER = OrderNumber.of(LocalDate.of(2026, 10, 22), 11);
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();

    /**
     * A mixed order: 10 cups at 100 000 from stock and 100 printed cups at 20 000 - 3 000 000 VND - released, so it is
     * IN_PRODUCTION. A deposit is 30 %, 900 000.
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
        order.release(HCM, "HCM", STAFF, NOW, Map.of());
        assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
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

    private static Map<UUID, Integer> printedAll(Order order) {
        return Map.of(print(order), 100);
    }

    @Nested
    @DisplayName("PACK-EARLY: the stock of a mixed order ships while it is printed")
    class PackEarly {

        @Test
        @DisplayName("stock handed over IN_PRODUCTION → PARTIALLY_FULFILLED, awaiting production; the prints are not at "
                + "packing; production completes → the prints follow: PACKED, SHIPPED, DELIVERED")
        void stockFirstThenPrints() {
            Order order = mixed(PaymentTerm.PREPAID, null, true);
            assertThat(order.printLinesReady()).isFalse();

            assertThat(order.recordHandover(handover(S1, stock(order), 10))).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.awaitingProduction()).isTrue();
            assertThat(order.printLinesReady()).isFalse();
            assertThat(order.packedAt()).as("a package left: returned, never cancelled").isNotNull();
            assertThat(order.pullTransitions()).extracting(OrderTransition::from, OrderTransition::to)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(OrderStatus.IN_PRODUCTION,
                            OrderStatus.PARTIALLY_FULFILLED));

            // Short of the print run: nothing moves.
            assertThat(order.completeProduction(Map.of(print(order), 99), "HCM", NOW)).isFalse();
            assertThat(order.completeProduction(printedAll(order), "HCM", NOW)).isTrue();
            assertThat(order.status()).as("the status follows the shipments").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.awaitingProduction()).isFalse();
            assertThat(order.printLinesReady()).isTrue();
            assertThat(order.pullDomainEvents()).anyMatch(OrderEvent.Released.class::isInstance);
            assertThat(order.completeProduction(printedAll(order), "HCM", NOW)).as("once").isFalse();

            assertThat(order.markPacked(NOW, STAFF)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.PACKED);
            order.recordHandover(handover(S2, print(order), 100));
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            order.recordShipmentDelivered(S1, NOW, STAFF);
            order.recordShipmentDelivered(S2, NOW, STAFF);
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.complete(NOW.plus(Duration.ofDays(8)), Duration.ofDays(7), false)).isTrue();
        }

        @Test
        @DisplayName("without partial shipments allowed, the stock does not leave ahead of the prints (409 "
                + "ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED) and the order stays IN_PRODUCTION")
        void refusedWithoutTheFlag() {
            Order order = mixed(PaymentTerm.PREPAID, null, false);
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S1, stock(order), 10)))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
            assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
            assertThat(order.shipments()).isEmpty();
            assertThat(order.awaitingProduction()).isFalse();
        }

        @Test
        @DisplayName("production completes before the packed stock left: READY_TO_FULFILL as usual; the stock may "
                + "still leave first (→ PARTIALLY_FULFILLED, nothing awaited)")
        void productionBeforeTheHandover() {
            Order order = mixed(PaymentTerm.PREPAID, null, true);
            assertThat(order.completeProduction(printedAll(order), "HCM", NOW)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
            assertThat(order.printLinesReady()).isTrue();

            order.recordHandover(handover(S1, stock(order), 10));
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.awaitingProduction()).isFalse();
        }

        @Test
        @DisplayName("staff close the print lines after the early shipment: nothing awaited, the order shows its "
                + "shipment, production finishing later changes nothing, the refund is what was paid for the prints")
        void printLinesClosedAfterTheEarlyShipment() {
            Order order = mixed(PaymentTerm.PREPAID, null, true);
            order.recordHandover(handover(S1, stock(order), 10));
            order.pullDomainEvents();

            BigDecimal closed = order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, STAFF, NOW);
            assertThat(closed).isEqualByComparingTo("2000000");
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(order.awaitingProduction()).isFalse();
            assertThat(order.billable()).isEqualByComparingTo("1000000");
            var event = order.pullDomainEvents().stream().filter(OrderEvent.RemainderClosed.class::isInstance)
                    .map(e -> ((OrderEvent.RemainderClosed) e).payload()).findFirst().orElseThrow();
            assertThat(event.lines()).singleElement().satisfies(line -> {
                assertThat(line.orderLineId()).isEqualTo(print(order));
                assertThat(line.quantity()).isEqualTo(100);
            });
            assertThat(event.refundableAmount()).isEqualByComparingTo("2000000");

            assertThat(order.completeProduction(printedAll(order), "HCM", NOW)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("held while it waits for production: production completing clears the wait, resolving goes back "
                + "to PARTIALLY_FULFILLED with the prints ready")
        void heldWhileAwaitingProduction() {
            Order order = mixed(PaymentTerm.PREPAID, null, true);
            order.recordHandover(handover(S1, stock(order), 10));
            order.putOnHold(HoldReason.CUSTOMER, null, STAFF, NOW);
            assertThat(order.printLinesReady()).isFalse();

            assertThat(order.completeProduction(printedAll(order), "HCM", NOW)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(order.statusBeforeHold()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            order.resolveHold("Khách xác nhận", STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.printLinesReady()).isTrue();
        }

        @Test
        @DisplayName("money per shipment: a credit order bills the stock shipment when it is delivered, the prints "
                + "later; an ON_DELIVERY deposit order's early COD is the stock less the deposit")
        void moneyPerShipment() {
            Order credit = mixed(PaymentTerm.CREDIT, null, true);
            credit.recordHandover(handover(S1, stock(credit), 10));
            credit.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(deliveries(credit)).singleElement()
                    .satisfies(d -> assertThat(d.billedAmount()).isEqualByComparingTo("1000000"));
            assertThat(credit.unbilled()).as("the prints, still on the order's side").isEqualByComparingTo("2000000");
            credit.completeProduction(printedAll(credit), "HCM", NOW);
            credit.markPacked(NOW, STAFF);
            credit.recordHandover(handover(S2, print(credit), 100));
            credit.recordShipmentDelivered(S2, NOW, STAFF);
            assertThat(deliveries(credit)).singleElement()
                    .satisfies(d -> assertThat(d.billedAmount()).isEqualByComparingTo("2000000"));
            assertThat(credit.billedAmount()).as("billed once in all").isEqualByComparingTo("3000000");
            assertThat(credit.unbilled()).isZero();

            Order cod = mixed(PaymentTerm.DEPOSIT, BalancePolicy.ON_DELIVERY, true);
            assertThat(cod.codFor(Map.of(stock(cod), 10), Map.of(), null)).isEqualByComparingTo("100000");
            cod.recordHandover(handover(S1, stock(cod), 10));
            assertThat(cod.shipments().getFirst().codAmount()).isEqualByComparingTo("100000");
            assertThat(cod.codFor(Map.of(print(cod), 100), Map.of(), null)).as("the rest of the balance")
                    .isEqualByComparingTo("2000000");
        }
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
            order.release(HCM, "HCM", STAFF, NOW, Map.of());
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
                    List.of(), null, List.of(new ShipmentHandover.Line(lids, 5))));
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
                    BalancePolicy.ON_DELIVERY)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), 30, BalancePolicy.ON_CREDIT);
            assertThat(order.balanceOnCredit()).isTrue();
            assertThat(order.creditTermDays()).isEqualTo(30);
            assertThat(order.creditAmount()).as("the balance after the 300 000 deposit").isEqualByComparingTo("700000");
        }

        @Test
        @DisplayName("only the deposit before the handover; no COD; each delivery bills its goods less the deposit not "
                + "yet used; DELIVERED until the receivables are paid")
        void billedOnDelivery() {
            Order order = mixed(PaymentTerm.DEPOSIT, BalancePolicy.ON_CREDIT, true);
            assertThat(order.amountDueBeforeHandover()).isZero();
            assertThat(order.amountDueOnDelivery()).isZero();
            assertThat(order.codFor(Map.of(stock(order), 10), Map.of(), null)).isZero();
            assertThat(order.creditAmount()).isEqualByComparingTo("2100000");

            order.recordHandover(handover(S1, stock(order), 10));
            assertThat(order.shipments().getFirst().codAmount()).isZero();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(deliveries(order)).singleElement().satisfies(d -> {
                assertThat(d.billedAmount()).as("1 000 000 of goods, the 900 000 deposit pays them first")
                        .isEqualByComparingTo("100000");
                assertThat(d.balancePolicy()).isEqualTo("ON_CREDIT");
                assertThat(d.creditTermDays()).isEqualTo(30);
            });
            order.completeProduction(printedAll(order), "HCM", NOW);
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

        /** Stock 10 x 200 000 (2M) and 4 printed x 2 000 000 (8M): 10M, released, IN_PRODUCTION, may ship in parts. */
        private Order tenMillion(PaymentTerm term, BalancePolicy policy) {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("HOP-PIZZA"), 10, Money.vnd(200_000), null),
                    Order.line(new Sku("LY-IN"), 4, Money.vnd(2_000_000), UUID.randomUUID())), NOW);
            order.applyTerms(term, term == PaymentTerm.DEPOSIT ? new BigDecimal("30") : null, null, policy);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.decidePartialShipment(true);
            order.submit();
            order.recordPayment(term == PaymentTerm.PREPAID ? order.total() : Money.vnd(3_000_000), NOW);
            order.release(HCM, "HCM", STAFF, NOW, Map.of());
            order.pullDomainEvents();
            return order;
        }

        private com.stockflow.contracts.OrderRemainderClosed closure(Order order) {
            return order.pullDomainEvents().stream().filter(OrderEvent.RemainderClosed.class::isInstance)
                    .map(e -> ((OrderEvent.RemainderClosed) e).payload()).findFirst().orElseThrow();
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
            order.recordPrintingStarted(NOW);
            order.completeProduction(Map.of(print(order), 4), "HCM", NOW);
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
            order.recordPrintingStarted(NOW);
            assertThat(code(catchThrowable(() -> order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null,
                    fee(9_000_000), STAFF, NOW)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
            assertThat(order.lines()).allMatch(line -> line.cancelledQuantity() == 0);
            assertThat(order.remainderFeeAmount()).isZero();
        }

        @Test
        @DisplayName("1: deposit 3M of 10M (carrier collects), 2M stock shipped with no COD, prints closed with a 2M "
                + "fee: the deposit keeps 1M of it, 1M is owed, nothing refunded")
        void depositKeepsTheFeeFirst() {
            Order order = tenMillion(PaymentTerm.DEPOSIT, BalancePolicy.ON_DELIVERY);
            order.recordHandover(handover(S1, stock(order), 10));
            assertThat(order.shipments().getFirst().codAmount()).isZero();
            order.recordPrintingStarted(NOW);
            order.closeRemainder(CancellationReasonCode.CUSTOMER_REQUEST, null, fee(2_000_000), STAFF, NOW);
            var closed = closure(order);
            assertThat(closed.retainedFeeAmount()).isEqualByComparingTo("1000000");
            assertThat(closed.owedAmount()).isEqualByComparingTo("1000000");
            assertThat(closed.refundableAmount()).isZero();
            assertThat(order.amountDue()).as("2M goods + 2M fee - 3M paid").isEqualByComparingTo("1000000");
        }

        @Test
        @DisplayName("2: partial shipment cannot be stopped while the order waits for production after its stock left "
                + "(409 ORDER_PARTIAL_SHIPMENT_LOCKED); once production completed it can")
        void notStoppedWhileAwaitingProduction() {
            Order order = tenMillion(PaymentTerm.PREPAID, null);
            order.recordHandover(handover(S1, stock(order), 10));
            assertThat(code(catchThrowable(() -> order.decidePartialShipment(false))))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_LOCKED);
            assertThat(order.allowPartialShipment()).isTrue();
            order.completeProduction(Map.of(print(order), 4), "HCM", NOW);
            assertThat(order.decidePartialShipment(false)).isTrue();
        }

        @Test
        @DisplayName("4: stock packed ahead of the prints, nothing handed over: cancelled with the printing fee; once a "
                + "shipment left it is returned instead")
        void packedAheadOfThePrintsIsStillCancelled() {
            Order order = tenMillion(PaymentTerm.PREPAID, null);
            order.recordPacked(NOW);
            order.recordPrintingStarted(NOW);
            assertThat(order.packedOnlyAheadOfPrints()).isTrue();
            assertThat(order.packedForGood()).isFalse();
            order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null, fee(2_000_000), STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.cancellationRetainedAmount()).isEqualByComparingTo("2000000");

            Order shipped = tenMillion(PaymentTerm.PREPAID, null);
            shipped.recordHandover(handover(S1, stock(shipped), 10));
            assertThat(shipped.packedForGood()).isTrue();
            assertThat(code(catchThrowable(shipped::requireNotPacked))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        @Test
        @DisplayName("7: a print line is withdrawn once every unit is shipped or closed, not before")
        void lineWithdrawnCountsShippedUnits() {
            Order order = tenMillion(PaymentTerm.PREPAID, null);
            assertThat(order.lineWithdrawn(print(order))).isFalse();
            order.recordHandover(handover(S1, stock(order), 10));
            order.completeProduction(Map.of(print(order), 4), "HCM", NOW);
            order.markPacked(NOW, STAFF);
            order.recordHandover(handover(S2, print(order), 3));
            assertThat(order.lineWithdrawn(print(order))).as("1 left").isFalse();
            order.closeRemainder(CancellationReasonCode.OUT_OF_STOCK, null, STAFF, NOW);
            assertThat(order.lineWithdrawn(print(order))).as("3 shipped + 1 closed").isTrue();
        }
    }
}
