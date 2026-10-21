package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
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
 * G2-3 on the aggregate alone: the balance policy of a deposit order at the handover (decision G3-Q4), what the
 * carrier collects per shipment (15 BR-08, 09 BR-04), and money taken back by a reversal (15 BR-06) - never silently
 * un-confirming the order.
 */
class OrderBalanceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");
    private static final OrderNumber NUMBER = OrderNumber.of(LocalDate.of(2026, 10, 21), 9);
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();

    /** 10 cups at 100 000 and 5 lids at 20 000: 1 100 000 VND; a deposit is 30 %, 330 000. */
    private static Order placed(PaymentTerm term, BalancePolicy policy) {
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null)), NOW);
        order.applyTerms(term, term == PaymentTerm.DEPOSIT ? new BigDecimal("30") : null, null, policy);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();
        return order;
    }

    /** Placed, paid as its terms want to be confirmed (all, or the deposit), released and packed. */
    private static Order packed(PaymentTerm term, BalancePolicy policy) {
        Order order = placed(term, policy);
        order.recordPayment(term == PaymentTerm.PREPAID ? order.total() : Money.vnd(330_000), NOW);
        order.release(HCM, "HCM", STAFF, NOW, Map.of());
        order.startPicking(STAFF, NOW);
        order.markPacked(NOW, STAFF);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    private static UUID cups(Order order) {
        return order.lines().get(0).id();
    }

    private static UUID lids(Order order) {
        return order.lines().get(1).id();
    }

    private static ShipmentHandover handover(UUID shipment, Object... lineAndQuantity) {
        List<ShipmentHandover.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < lineAndQuantity.length; i += 2) {
            lines.add(new ShipmentHandover.Line((UUID) lineAndQuantity[i], (Integer) lineAndQuantity[i + 1]));
        }
        return new ShipmentHandover(shipment, lines, NOW, STAFF);
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
    @DisplayName("terms")
    class Terms {

        @Test
        @DisplayName("a deposit order pays its balance before delivery unless told otherwise; other terms have none")
        void defaults() {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP"), 1, Money.vnd(1_000), null)), NOW);
            order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"));
            assertThat(order.balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
            order.applyTerms(PaymentTerm.PREPAID, null);
            assertThat(order.balancePolicy()).isNull();
            assertThat(code(catchThrowable(() -> order.applyTerms(PaymentTerm.PREPAID, null, null,
                    BalancePolicy.ON_DELIVERY)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Nested
    @DisplayName("the handover (G3-Q4)")
    class Handover {

        @Test
        @DisplayName("BEFORE_DELIVERY: the deposit confirms and releases it; the balance blocks the handover, warned "
                + "from packing; paid, the goods leave")
        void beforeDelivery() {
            Order order = packed(PaymentTerm.DEPOSIT, BalancePolicy.BEFORE_DELIVERY);
            assertThat(order.depositReceivedAt()).isNotNull();
            assertThat(order.amountDueBeforeHandover()).isEqualByComparingTo("770000");
            assertThat(order.amountDueOnDelivery()).isZero();
            assertThat(code(catchThrowable(() -> order.markShipped(NOW, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
            assertThat(code(catchThrowable(() -> order.recordHandover(handover(S1, cups(order), 2)))))
                    .as("a partial shipment too").isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
            order.recordPayment(Money.vnd(500_000), NOW);
            assertThat(order.amountDueBeforeHandover()).isEqualByComparingTo("270000");
            order.recordPayment(Money.vnd(270_000), NOW);
            assertThat(order.amountDueBeforeHandover()).isZero();
            assertThat(order.markShipped(NOW, STAFF)).isTrue();
            assertThat(order.codAmount()).isZero();
        }

        @Test
        @DisplayName("ON_DELIVERY, split: the deposit lets the goods go; each shipment's COD is its value less the money "
                + "paid ahead the earlier ones did not use; delivery bills exactly the COD; a return bills nothing")
        void onDeliverySplit() {
            Order order = packed(PaymentTerm.DEPOSIT, BalancePolicy.ON_DELIVERY);
            assertThat(order.amountDueBeforeHandover()).isZero();
            assertThat(order.amountDueOnDelivery()).isEqualByComparingTo("770000");

            order.recordHandover(handover(S1, cups(order), 5));                   // 500 000
            order.recordHandover(handover(S2, cups(order), 5, lids(order), 5));   // 600 000
            assertThat(order.shipments().get(0).codAmount()).isEqualByComparingTo("170000");
            assertThat(order.shipments().get(1).codAmount()).isEqualByComparingTo("600000");
            assertThat(order.codInTransit()).isEqualByComparingTo("770000");

            order.pullDomainEvents();
            order.recordShipmentDelivered(S1, NOW, STAFF);
            assertThat(deliveries(order)).singleElement().satisfies(e -> {
                assertThat(e.billedAmount()).isEqualByComparingTo("170000");
                assertThat(e.balancePolicy()).isEqualTo("ON_DELIVERY");
                assertThat(e.shipmentId()).isEqualTo(S1);
            });
            assertThat(order.codInTransit()).isEqualByComparingTo("600000");

            order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, NOW, STAFF);
            assertThat(order.codInTransit()).as("still with the carrier").isEqualByComparingTo("600000");
            order.recordShipmentReturned(S2, NOW, STAFF);
            assertThat(order.codInTransit()).isZero();
            assertThat(order.billedAmount()).isEqualByComparingTo("170000");

            // Shipped again later: the deposit is all used by S1, so the whole value is collected.
            order.recordHandover(handover(UUID.randomUUID(), cups(order), 5, lids(order), 5));
            assertThat(order.shipments().get(2).codAmount()).isEqualByComparingTo("600000");
        }

        @Test
        @DisplayName("ON_DELIVERY, whole order: the COD is what is left, fixed at the handover and billed at delivery")
        void onDeliveryWholeOrder() {
            Order order = packed(PaymentTerm.DEPOSIT, BalancePolicy.ON_DELIVERY);
            order.markShipped(NOW, STAFF);
            assertThat(order.codAmount()).isEqualByComparingTo("770000");
            assertThat(order.codInTransit()).isEqualByComparingTo("770000");
            order.pullDomainEvents();
            order.markDelivered(NOW, STAFF);
            assertThat(deliveries(order)).singleElement()
                    .satisfies(e -> assertThat(e.billedAmount()).isEqualByComparingTo("770000"));
            assertThat(order.codInTransit()).isZero();
            assertThat(order.complete(NOW.plusSeconds(86_400 * 30L), java.time.Duration.ofDays(7), false))
                    .as("not until the carrier's money arrives").isFalse();
            order.recordPayment(Money.vnd(770_000), NOW, true);
            assertThat(order.complete(NOW.plusSeconds(86_400 * 30L), java.time.Duration.ofDays(7), false)).isTrue();
        }

        @Test
        @DisplayName("a prepaid order owes everything before its goods leave, nothing once paid")
        void otherTerms() {
            Order unpaid = placed(PaymentTerm.PREPAID, null);
            assertThat(unpaid.amountDueBeforeHandover()).isEqualByComparingTo("1100000");
            Order prepaid = packed(PaymentTerm.PREPAID, null);
            assertThat(prepaid.amountDueBeforeHandover()).isZero();
            assertThat(prepaid.codInTransit()).isZero();
        }
    }

    @Nested
    @DisplayName("money taken back (15 BR-06)")
    class Reversal {

        @Test
        @DisplayName("a confirmed prepaid order left short goes ON_HOLD (PAYMENT), never back to PENDING_PAYMENT; "
                + "the hold waits for the money")
        void confirmedPrepaidIsHeld() {
            Order order = placed(PaymentTerm.PREPAID, null);
            order.recordPayment(Money.vnd(600_000), NOW);
            assertThat(order.recordPayment(Money.vnd(500_000), NOW)).as("paid in two transfers").isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.confirmationThreshold()).isEqualByComparingTo("1100000");

            assertThat(order.reversePayment(Money.vnd(500_000), false, "wrong order", STAFF, NOW)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(order.holdReason()).isEqualTo(HoldReason.PAYMENT);
            assertThat(order.statusBeforeHold()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.holdNote()).contains("wrong order");
            assertThat(order.paidAmount()).isEqualByComparingTo("600000");
            assertThat(order.paidInFullAt()).isNull();
            assertThat(order.termsMet()).isFalse();

            assertThat(code(catchThrowable(() -> order.resolveHold("paid?", STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
            assertThat(order.recordPayment(Money.vnd(500_000), NOW)).as("on hold: money does not move it").isFalse();
            order.resolveHold("the right transfer arrived", STAFF, NOW);
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("a deposit order in production without its deposit is held; production does not resume unpaid")
        void depositInProductionIsHeld() {
            UUID snapshot = UUID.randomUUID();
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP-PRINTED"), 10, Money.vnd(1_000), snapshot)), NOW);
            order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("50"));
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.submit();
            order.recordPayment(Money.vnd(5_000), NOW);
            order.release(HCM, "HCM", STAFF, NOW, Map.of());
            assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
            assertThat(order.confirmationThreshold()).isEqualByComparingTo("5000");

            assertThat(order.reversePayment(Money.vnd(5_000), false, "duplicate", STAFF, NOW)).isTrue();
            assertThat(order.depositReceivedAt()).isNull();
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(code(catchThrowable(() -> order.resolveHold("go on", STAFF, NOW))))
                    .isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
        }

        @Test
        @DisplayName("before confirmation it just waits for more; after the handover it owes again; part of a "
                + "receivable's money comes off what receivables paid")
        void otherStages() {
            Order pending = placed(PaymentTerm.PREPAID, null);
            pending.recordPayment(Money.vnd(100_000), NOW);
            assertThat(pending.confirmationThreshold()).isNull();
            assertThat(pending.reversePayment(Money.vnd(100_000), false, "x", STAFF, NOW)).isFalse();
            assertThat(pending.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(pending.paidAmount()).isZero();

            Order shipped = packed(PaymentTerm.PREPAID, null);
            shipped.markShipped(NOW, STAFF);
            assertThat(shipped.confirmationThreshold()).isNull();
            assertThat(shipped.reversePayment(Money.vnd(100_000), false, "x", STAFF, NOW)).isFalse();
            assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(shipped.amountDue()).isEqualByComparingTo("100000");

            Order paidOnReceivable = placed(PaymentTerm.PREPAID, null);
            paidOnReceivable.recordPayment(Money.vnd(300_000), NOW, true);
            paidOnReceivable.reversePayment(Money.vnd(300_000), true, "x", STAFF, NOW);
            assertThat(paidOnReceivable.receivablePaidAmount()).isZero();
        }
    }

    @Nested
    @DisplayName("review of G2-3")
    class Review {

        @Test
        @DisplayName("COD at the handover counts the ledger's money the order has not heard of yet (no double "
                + "collection): 3M deposit counted, 2M allocated a moment ago - a 5M shipment collects nothing")
        void codFromTheLedger() {
            Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                    Order.line(new Sku("CUP"), 2, Money.vnd(5_000_000), null)), NOW);
            order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), null, BalancePolicy.ON_DELIVERY);
            order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
            order.submit();
            order.recordPayment(Money.vnd(3_000_000), NOW);
            order.release(HCM, "HCM", STAFF, NOW, Map.of());
            order.startPicking(STAFF, NOW);
            Order.LedgerMoney ledger = new Order.LedgerMoney(new BigDecimal("5000000"), new BigDecimal("5000000"));
            order.recordHandover(handover(S1, order.lines().getFirst().id(), 1), ledger);
            assertThat(order.shipments().getFirst().codAmount()).isZero();
            order.markPacked(NOW, STAFF);
            order.recordHandover(handover(S2, order.lines().getFirst().id(), 1), ledger);
            assertThat(order.shipments().get(1).codAmount()).isEqualByComparingTo("5000000");
        }

        @Test
        @DisplayName("BEFORE_DELIVERY: the ledger's money opens the gate before the order's count catches up")
        void gateFromTheLedger() {
            Order order = packed(PaymentTerm.DEPOSIT, BalancePolicy.BEFORE_DELIVERY);   // 330 000 of 1 100 000
            assertThat(code(catchThrowable(() -> order.markShipped(NOW, STAFF,
                    new Order.LedgerMoney(new BigDecimal("1099999"), null))))).isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
            assertThat(order.markShipped(NOW, STAFF, new Order.LedgerMoney(new BigDecimal("1100000"), null))).isTrue();
        }

        @Test
        @DisplayName("a reversal reaching an order cancelled in the meantime: paid falls, what was kept never exceeds "
                + "it, no hold, no failure (fee 500 000 kept of 1 100 000, then 1 100 000 reversed)")
        void reversalAfterCancellation() {
            Order order = placed(PaymentTerm.PREPAID, null);
            order.recordPayment(order.total(), NOW);
            order.recordPrintingStarted(NOW);
            order.cancel(com.stockflow.order.api.CancellationReasonCode.CUSTOMER_REQUEST, "changed plans", null,
                    new com.stockflow.order.api.CancellationFee(new BigDecimal("500000"), "blanks used", "agreed"),
                    STAFF, NOW);
            assertThat(order.cancellationRetainedAmount()).isEqualByComparingTo("500000");
            assertThat(order.reversePayment(Money.vnd(1_100_000), false, "wrong order", STAFF, NOW)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.paidAmount()).isZero();
            assertThat(order.cancellationRetainedAmount()).isZero();
        }

        @Test
        @DisplayName("terms are measured on what the order is worth now: 5 cups closed, 600 000 billable - 600 000 "
                + "paid keeps it confirmed after 500 000 of 1 100 000 is taken back")
        void thresholdOnBillable() {
            Order order = placed(PaymentTerm.PREPAID, null);
            order.recordPayment(order.total(), NOW);
            order.lines().getFirst().closeUnits(5);
            assertThat(order.billable()).isEqualByComparingTo("600000");
            assertThat(order.confirmationThreshold()).isEqualByComparingTo("600000");
            assertThat(order.reversePayment(Money.vnd(500_000), false, "wrong order", STAFF, NOW)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.termsMet()).isTrue();
            assertThat(order.reversePayment(Money.vnd(1), false, "one more", STAFF, NOW)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
        }
    }
}
