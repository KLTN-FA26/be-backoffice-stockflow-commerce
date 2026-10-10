package com.stockflow.order.internal.domain;

import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final OrderNumber NUMBER = OrderNumber.of(LocalDate.of(2026, 9, 7), 431);

    private static Order draftWithTwoLines() {
        return Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("SOFA-3S-GREY"), 2, Money.vnd(12_000_000), null),
                Order.line(new Sku("TABLE-OAK-160"), 1, Money.vnd(8_000_000), null)), NOW);
    }

    private static final UUID STAFF = UUID.randomUUID();

    private static void paidInFull(Order order) {
        order.recordPayment(order.total(), NOW);
    }

    private static void cancel(Order order, String note) {
        order.cancel(CancellationReasonCode.OTHER, note, null, STAFF, NOW);
    }

    private static Order submittedOrder() {
        Order order = draftWithTwoLines();
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();
        order.pullDomainEvents();
        return order;
    }

    @Test
    @DisplayName("the total is derived from the lines and cannot be set independently")
    void totalIsDerived() {
        assertThat(draftWithTwoLines().total()).isEqualTo(Money.vnd(32_000_000));
    }

    @Test
    @DisplayName("an order number formats as SO-yyyyMMdd-nnnnnn")
    void orderNumberFormat() {
        assertThat(NUMBER.value()).isEqualTo("SO-20260907-000431");
    }

    @Test
    @DisplayName("cannot be submitted while a line holds no stock")
    void submitRequiresEveryLineToBeReserved() {
        Order order = draftWithTwoLines();
        order.attachReservations(order.lines().getFirst().id(), List.of(UUID.randomUUID()));

        // The guard that catches the day someone adds a checkout path that forgets to reserve.
        assertThatThrownBy(order::submit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 line(s) hold no stock reservation");

        // And the refusal leaves nothing half-done: the order is still a DRAFT, not an order
        // claiming to be awaiting payment for stock nobody set aside.
        assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    @DisplayName("submitting announces OrderPlaced with the full line detail")
    void submitPublishesOrderPlaced() {
        Order order = draftWithTwoLines();
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));

        order.submit();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(OrderEvent.Placed.class, placed ->
                        assertThat(placed.payload().lines()).hasSize(2));
    }

    @Test
    @DisplayName("a line cannot be reserved twice")
    void attachingASecondReservationIsRejected() {
        Order order = draftWithTwoLines();
        UUID lineId = order.lines().getFirst().id();
        order.attachReservations(lineId, List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> order.attachReservations(lineId, List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already holds reservation");
    }

    @Test
    @DisplayName("a line drawn from two lots keeps both holds")
    void aLineCanCarrySeveralReservations() {
        Order order = draftWithTwoLines();
        UUID lot1 = UUID.randomUUID();
        UUID lot2 = UUID.randomUUID();
        UUID lineId = order.lines().getFirst().id();

        order.attachReservations(lineId, List.of(lot1, lot2));

        // Keeping only the first would strand the second: cancelling would release one lot and
        // leave the other held until the sweeper expired it half an hour later.
        assertThat(order.lines().getFirst().reservationIds()).containsExactly(lot1, lot2);
        assertThat(order.reservationIds()).contains(lot1, lot2);
    }

    @Test
    @DisplayName("cancellation is refused once the goods have shipped")
    void cannotCancelAfterShipment() {
        Order order = submittedOrder();
        paidInFull(order);
        order.startFulfilment();
        order.pullDomainEvents();

        assertThat(order.status().canTransitionTo(OrderStatus.CANCELLED)).isTrue();

        // BR-031: from SHIPPED the goods are with the carrier and the process is a return.
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("a refused cancellation is ORDER_NOT_CANCELLABLE (409) naming the status, not a 400")
    void cancellingFromANonCancellableStatusIsAConflict() {
        Order order = submittedOrder();
        cancel(order, "customer changed their mind");

        assertThatThrownBy(() -> cancel(order, "again"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("is CANCELLED and can no longer be cancelled")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("money arriving for a cancelled order is counted, and the order stays cancelled")
    void payingACancelledOrderChangesNoStatus() {
        Order order = submittedOrder();
        cancel(order, "changed mind");

        assertThat(order.recordPayment(order.total(), NOW)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.paidAmount()).isEqualByComparingTo("32000000");
    }

    @Test
    @DisplayName("cancelling twice is refused and keeps the first reason")
    void cancellingTwiceIsAConflict() {
        Order order = submittedOrder();
        cancel(order, "first reason");

        assertThatThrownBy(() -> cancel(order, "second reason")).isInstanceOf(BusinessException.class);
        assertThat(order.cancellationReason()).isEqualTo("OTHER: first reason");
    }

    @Test
    @DisplayName("OTHER without a note is refused; a known code needs none")
    void cancellationRequiresAReason() {
        Order order = submittedOrder();

        assertThatThrownBy(() -> cancel(order, "  "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        order.cancel(CancellationReasonCode.OUT_OF_STOCK, null, null, STAFF, NOW);
        assertThat(order.cancellationReason()).isEqualTo("OUT_OF_STOCK");
        assertThat(order.cancellationReasonCode()).isEqualTo(CancellationReasonCode.OUT_OF_STOCK);
    }

    // ------------------------------------------------------------------ SCRUM-460

    @Test
    @DisplayName("cancelling announces OrderCancelled with the money received and what is refundable")
    void cancellationAnnouncesItself() {
        Order order = submittedOrder();
        paidInFull(order);
        order.pullDomainEvents();

        order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, "changed supplier", new BigDecimal("25"), STAFF, NOW);

        assertThat(order.cancellationRetainedAmount()).isEqualByComparingTo("8000000");
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Cancelled.class, e -> {
            assertThat(e.payload().orderId()).isEqualTo(order.id().value());
            assertThat(e.payload().previousStatus()).isEqualTo("CONFIRMED");
            assertThat(e.payload().reasonCode()).isEqualTo("CUSTOMER_REQUEST");
            assertThat(e.payload().note()).isEqualTo("changed supplier");
            assertThat(e.payload().paidAmount()).isEqualByComparingTo("32000000");
            assertThat(e.payload().refundableAmount()).isEqualByComparingTo("24000000");
            assertThat(e.payload().currency()).isEqualTo("VND");
            assertThat(e.payload().cancelledBy()).isEqualTo(STAFF);
        });
    }

    @Test
    @DisplayName("an unpaid order refunds nothing; a retained share outside 0-100 is refused")
    void unpaidCancellationAndRetainedBounds() {
        Order order = submittedOrder();
        assertThatThrownBy(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, new BigDecimal("101"),
                STAFF, NOW)).extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        order.cancel(CancellationReasonCode.PAYMENT_NOT_RECEIVED, null, null, null, NOW);
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Cancelled.class, e -> {
            assertThat(e.payload().paidAmount()).isEqualByComparingTo("0");
            assertThat(e.payload().refundableAmount()).isEqualByComparingTo("0");
            assertThat(e.payload().cancelledBy()).isNull();
        });
    }

    @Test
    @DisplayName("kltn-docs 15 BR-02: prepaid is CONFIRMED only when paid in full; part payments add up")
    void prepaidConfirmsWhenPaidInFull() {
        Order order = submittedOrder();
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.UNPAID);

        assertThat(order.recordPayment(Money.vnd(12_000_000), NOW)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);

        assertThat(order.recordPayment(Money.vnd(20_000_000), NOW)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.paidInFullAt()).isEqualTo(NOW);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("a deposit order: terms at placement, CONFIRMED on the deposit, the balance never moves it")
    void depositConfirmsOnTheDeposit() {
        UUID snapshot = UUID.randomUUID();
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ-WHITE"), 500, Money.vnd(2_000), snapshot)), NOW);
        order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"));
        assertThat(order.depositRequired()).isEqualByComparingTo("300000");
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();

        assertThat(order.recordPayment(Money.vnd(100_000), NOW)).isFalse();
        assertThat(order.recordPayment(Money.vnd(200_000), NOW)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.depositReceivedAt()).isEqualTo(NOW);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);

        order.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of());
        assertThat(order.recordPayment(Money.vnd(700_000), NOW)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("terms: CREDIT waits for credit limits, a deposit needs a percentage in (0, 100), prepaid takes none")
    void termsAreChecked() {
        assertThatThrownBy(() -> draftWithTwoLines().applyTerms(PaymentTerm.CREDIT, null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PAYMENT_TERM_NOT_ALLOWED);
        assertThatThrownBy(() -> draftWithTwoLines().applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("100")))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> draftWithTwoLines().applyTerms(PaymentTerm.PREPAID, new BigDecimal("30")))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        Order prepaid = draftWithTwoLines();
        prepaid.applyTerms(null, null);
        assertThat(prepaid.paymentTerm()).isEqualTo(PaymentTerm.PREPAID);
        assertThat(prepaid.depositRequired()).isNull();
    }

    @Test
    @DisplayName("a customer cancels on their own only until the order is released")
    void customerCancelsDirectlyOnlyBeforeRelease() {
        assertThat(OrderStatus.PENDING_PAYMENT.customerMayCancelDirectly()).isTrue();
        assertThat(OrderStatus.CONFIRMED.customerMayCancelDirectly()).isTrue();
        for (OrderStatus later : List.of(OrderStatus.IN_PRODUCTION, OrderStatus.READY_TO_FULFILL,
                OrderStatus.IN_FULFILMENT, OrderStatus.ON_HOLD, OrderStatus.SHIPPED)) {
            assertThat(later.customerMayCancelDirectly()).as(later.name()).isFalse();
        }
    }

    @Test
    @DisplayName("the status machine has no path out of a terminal state")
    void terminalStatesAreTerminal() {
        for (OrderStatus terminal : List.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED,
                OrderStatus.RETURNED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as("%s must not move to %s", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("only the statuses that actually hold stock report that they do")
    void holdsStockMatchesTheLifecycle() {
        assertThat(OrderStatus.DRAFT.holdsStock()).isFalse();          // nothing reserved yet
        assertThat(OrderStatus.PENDING_PAYMENT.holdsStock()).isTrue();
        assertThat(OrderStatus.CONFIRMED.holdsStock()).isTrue();
        assertThat(OrderStatus.SHIPPED.holdsStock()).isFalse();        // already deducted
        assertThat(OrderStatus.CANCELLED.holdsStock()).isFalse();
    }

    @Test
    @DisplayName("guest checkout requires and retains immutable two-level address snapshots")
    void guestCheckoutKeepsAddressSnapshots() {
        var address = new OrderAddressSnapshot("Minh", "0901234567", "12 Nguyen Hue", null,
                "26734", "Ben Nghe", "79", "Ho Chi Minh City", "VN", null);
        Order guest = Order.guestDraft(NUMBER, UUID.randomUUID(), List.of(
                Order.line(new Sku("TABLE-OAK-160"), 1, Money.vnd(8_000_000), null)),
                NOW, "Minh", "minh@example.com", "0901234567", address, address);

        assertThat(guest.customerId()).isNull();
        assertThat(guest.contactEmail()).isEqualTo("minh@example.com");
        assertThat(guest.shippingAddress()).isEqualTo(address);
    }

    // ------------------------------------------------------------------ release (SCRUM-423)

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();

    private static Order paidOrder(boolean withPrintLine) {
        UUID snapshot = UUID.randomUUID();
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ-WHITE"), 500, Money.vnd(2_000), withPrintLine ? snapshot : null),
                Order.line(new Sku("LID-90MM"), 500, Money.vnd(300), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();
        paidInFull(order);
        order.pullDomainEvents();
        return order;
    }

    @Test
    @DisplayName("release with print lines: IN_PRODUCTION, one event carrying only the print lines and their sample")
    void releaseToProduction() {
        Order order = paidOrder(true);
        OrderLine print = order.printLines().getFirst();
        UUID sample = UUID.randomUUID();

        order.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of(print.id(), sample));

        assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        assertThat(order.warehouseId()).isEqualTo(HCM);
        assertThat(order.releasedBy()).isEqualTo(COORDINATOR);
        var events = order.pullDomainEvents();
        assertThat(events).singleElement().isInstanceOfSatisfying(OrderEvent.LinesReleased.class, e -> {
            assertThat(e.payload().warehouseId()).isEqualTo(HCM);
            assertThat(e.payload().lines()).singleElement().satisfies(l -> {
                assertThat(l.orderLineId()).isEqualTo(print.id());
                assertThat(l.blankSku()).isEqualTo("CUP-12OZ-WHITE");
                assertThat(l.quantity()).isEqualTo(500);
                assertThat(l.approvedSampleId()).isEqualTo(sample);
            });
        });
        // Fulfilment waits for production (BR-PRD-06).
        assertThatThrownBy(order::startFulfilment).isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    @DisplayName("release with nothing to print: READY_TO_FULFILL and OrderReleased")
    void releaseStockOnly() {
        Order order = paidOrder(false);
        order.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of());
        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Released.class,
                e -> assertThat(e.payload().warehouseCode()).isEqualTo("HCM"));
        order.startFulfilment();
        assertThat(order.status()).isEqualTo(OrderStatus.IN_FULFILMENT);
    }

    @Test
    @DisplayName("an unpaid order is not released; a paid order with print lines does not skip production")
    void releaseRefusals() {
        Order unpaid = draftWithTwoLines();
        unpaid.lines().forEach(line -> unpaid.attachReservations(line.id(), List.of(UUID.randomUUID())));
        unpaid.submit();
        assertThatThrownBy(() -> unpaid.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of()))
                .isInstanceOf(InvalidOrderTransitionException.class);

        Order printed = paidOrder(true);
        assertThatThrownBy(printed::startFulfilment).isInstanceOf(InvalidOrderTransitionException.class);

        Order released = paidOrder(false);
        released.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of());
        assertThatThrownBy(() -> released.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of()))
                .isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    @DisplayName("BR-PRD-09: a deposit order is not released before its deposit is in")
    void depositOrder() {
        Order deposit = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ-WHITE"), 500, Money.vnd(2_000), UUID.randomUUID())), NOW);
        deposit.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"));
        deposit.lines().forEach(line -> deposit.attachReservations(line.id(), List.of(UUID.randomUUID())));
        deposit.submit();

        assertThatThrownBy(() -> deposit.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ORDER_DEPOSIT_NOT_RECEIVED));

        deposit.recordPayment(Money.vnd(300_000), NOW);
        deposit.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of());
        assertThat(deposit.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
    }

    @Test
    @DisplayName("BR-PRD-06: READY_TO_FULFILL only once the good units cover every print line")
    void productionCompletes() {
        Order order = paidOrder(true);
        UUID line = order.printLines().getFirst().id();
        order.release(HCM, "HCM", COORDINATOR, NOW, java.util.Map.of(line, UUID.randomUUID()));
        order.pullDomainEvents();

        assertThat(order.completeProduction(java.util.Map.of(line, 300), "HCM", NOW)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        assertThat(order.completeProduction(java.util.Map.of(line, 500), "HCM", NOW)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOf(OrderEvent.Released.class);
        // Again (a redelivered event): nothing more happens.
        assertThat(order.completeProduction(java.util.Map.of(line, 500), "HCM", NOW)).isFalse();
    }
}
