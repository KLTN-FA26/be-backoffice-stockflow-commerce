package com.stockflow.order.internal.domain;

import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
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

    /**
     * Its contract sent and signed with nothing due on signing - CONFIRMED - shipping from {@link #HCM}: since 4B-10 the
     * one way an order is confirmed (a free replacement of a return aside), its money on the contract's installments.
     */
    private static void confirmedByItsContract(Order order) {
        order.sendContract(STAFF, NOW);
        order.contractSigned(false, false, STAFF, NOW);
        order.chooseShippingWarehouse(HCM, true, STAFF, NOW);
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
        Order order = draftWithTwoLines();
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        confirmedByItsContract(order);
        order.release(HCM, "HCM", COORDINATOR, NOW);
        order.startPicking(COORDINATOR, NOW);
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
    @DisplayName("cancelling announces OrderCancelled; the money is the installments' to settle, never the order's")
    void cancellationAnnouncesItself() {
        Order order = draftWithTwoLines();
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        confirmedByItsContract(order);
        order.pullDomainEvents();
        // Decision 2026-10-17: nothing is kept before printing; once printing started a share may be.
        assertThatThrownBy(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, "changed supplier",
                new BigDecimal("25"), STAFF, NOW)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING));
        order.restoreFulfilmentFacts(NOW, null);   // printing started (the Production Order records it, 17 BR-11)

        order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, "changed supplier", new BigDecimal("25"), STAFF, NOW);

        // 4B-9, 4B-10: the share is settled by payment against the installments in the caller's transaction; the
        // order keeps nothing and announces no money.
        assertThat(order.cancellationRetainedAmount()).isEqualByComparingTo("0");
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Cancelled.class, e -> {
            assertThat(e.payload().orderId()).isEqualTo(order.id().value());
            assertThat(e.payload().previousStatus()).isEqualTo("CONFIRMED");
            assertThat(e.payload().reasonCode()).isEqualTo("CUSTOMER_REQUEST");
            assertThat(e.payload().note()).isEqualTo("changed supplier");
            assertThat(e.payload().paidAmount()).isEqualByComparingTo("0");
            assertThat(e.payload().refundableAmount()).isEqualByComparingTo("0");
            assertThat(e.payload().owedAmount()).isEqualByComparingTo("0");
            assertThat(e.payload().currency()).isEqualTo("VND");
            assertThat(e.payload().cancelledBy()).isEqualTo(STAFF);
        });
    }

    @Test
    @DisplayName("a retained share outside 0-100, or past two decimals, is refused; the system cancels in no one's name")
    void unpaidCancellationAndRetainedBounds() {
        Order order = submittedOrder();
        assertThatThrownBy(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, new BigDecimal("101"),
                STAFF, NOW)).extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, new BigDecimal("12.345"),
                STAFF, NOW)).extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(order.status()).as("a refused cancellation leaves it as it was").isEqualTo(OrderStatus.PENDING_PAYMENT);

        order.cancel(CancellationReasonCode.PAYMENT_NOT_RECEIVED, null, null, null, NOW);
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Cancelled.class, e -> {
            assertThat(e.payload().paidAmount()).isEqualByComparingTo("0");
            assertThat(e.payload().refundableAmount()).isEqualByComparingTo("0");
            assertThat(e.payload().cancelledBy()).isNull();
        });
    }

    // ------------------------------------------------------------------ confirmation (4B-5, 4B-6; 4B-10)

    @Test
    @DisplayName("an order placed at checkout waits in PENDING_PAYMENT: no money in the system confirms it any more")
    void checkoutOrderIsNeverConfirmedByMoney() {
        Order order = submittedOrder();
        assertThat(order.confirmByInstallments(NOW)).as("not an order of a contract").isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.paidByContract()).isFalse();
    }

    @Test
    @DisplayName("15 BR-07: signed with an installment on signing it waits for it; without one it is CONFIRMED at once")
    void signedOrderIsConfirmedByItsInstallments() {
        Order waits = draftWithTwoLines();
        waits.sendContract(STAFF, NOW);
        waits.contractSigned(false, true, STAFF, NOW);
        assertThat(waits.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(waits.paidByContract()).isTrue();
        assertThat(waits.placedAt()).as("placed when signed (17 §4.11)").isEqualTo(NOW);
        assertThat(waits.pullDomainEvents()).singleElement().isInstanceOf(OrderEvent.Placed.class);
        assertThat(waits.confirmByInstallments(NOW)).isTrue();
        assertThat(waits.status()).isEqualTo(OrderStatus.CONFIRMED);

        Order atOnce = draftWithTwoLines();
        atOnce.sendContract(STAFF, NOW);
        atOnce.contractSigned(false, false, STAFF, NOW);
        assertThat(atOnce.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("15 BR-11: signed while what it pays after delivery does not fit - held for credit until accounting decides")
    void creditHoldAtSigning() {
        Order over = draftWithTwoLines();
        over.sendContract(STAFF, NOW);
        over.contractSigned(true, true, STAFF, NOW);
        assertThat(over.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(over.holdReason()).isEqualTo(com.stockflow.order.api.HoldReason.CREDIT);
        over.approveCredit(STAFF, NOW, true);
        assertThat(over.status()).as("approved, it still waits for its installment on signing")
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThatThrownBy(() -> over.approveCredit(STAFF, NOW, false))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD);
    }

    @Test
    @DisplayName("delivered from SHIPPED, announced with its total - and none of the money ledger's fields (4B-10)")
    void delivery() {
        Order order = new Order(OrderId.newId(), NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("SOFA-3S-GREY"), 1, Money.vnd(12_000_000), null)), OrderStatus.SHIPPED, NOW, null, 0L);

        order.markDelivered(NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Delivered.class, e -> {
            assertThat(e.payload().orderTotal()).isEqualByComparingTo("12000000");
            assertThat(e.payload().paymentTerm()).isNull();
            assertThat(e.payload().paidAmount()).isNull();
            assertThat(e.payload().creditTermDays()).isNull();
            assertThat(e.payload().deliveredAt()).isEqualTo(NOW);
        });
        assertThatThrownBy(() -> order.markDelivered(NOW)).isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    @DisplayName("a customer never cancels on their own: they ask, and Sales decides (4B-5, 17 §4.8)")
    void customerNeverCancelsDirectly() {
        // 4B-5 (17 §4.8): an order the customer sees is bound by its contract - they ask, Sales decides.
        for (OrderStatus any : OrderStatus.values()) {
            assertThat(any.customerMayCancelDirectly()).as(any.name()).isFalse();
        }
    }

    @Test
    @DisplayName("the status machine has no path out of a terminal state")
    void terminalStatesAreTerminal() {
        for (OrderStatus terminal : List.of(OrderStatus.CLOSED, OrderStatus.CANCELLED)) {
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

    // ------------------------------------------------------------------ release (SCRUM-423)

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();

    private static Order paidOrder(boolean withPrintLine) {
        UUID snapshot = UUID.randomUUID();
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ-WHITE"), 500, Money.vnd(2_000), withPrintLine ? snapshot : null),
                Order.line(new Sku("LID-90MM"), 500, Money.vnd(300), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        confirmedByItsContract(order);
        order.pullDomainEvents();
        return order;
    }

    @Test
    @DisplayName("release with print lines: READY_TO_FULFILL like any order - printed outside the system, the goods are in stock")
    void releaseWithPrintLines() {
        Order order = paidOrder(true);

        order.release(HCM, "HCM", COORDINATOR, NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(order.warehouseId()).isEqualTo(HCM);
        assertThat(order.releasedBy()).isEqualTo(COORDINATOR);
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Released.class,
                e -> assertThat(e.payload().warehouseCode()).isEqualTo("HCM"));
        // Opening the task picks every line and records once that it is open (review of #150).
        assertThat(order.startPicking(COORDINATOR, NOW)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
        assertThat(order.fulfilmentStartedAt()).isEqualTo(NOW);
        assertThat(order.startPicking(COORDINATOR, NOW.plusSeconds(60))).isFalse();
        assertThat(order.fulfilmentStartedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("SCRUM-502: the date the customer asked for stays on the line through the release")
    void releaseKeepsTheRequestedDeliveryDate() {
        java.time.LocalDate asked = java.time.LocalDate.of(2026, 12, 15);
        UUID snapshot = UUID.randomUUID();
        Order order = Order.draft(NUMBER, UUID.randomUUID(), UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ-WHITE"), 500, Money.vnd(2_000), snapshot).withRequestedDeliveryDate(asked),
                Order.line(new Sku("CUP-12OZ-BLACK"), 300, Money.vnd(2_000), UUID.randomUUID()),
                Order.line(new Sku("LID-90MM"), 500, Money.vnd(300), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        confirmedByItsContract(order);
        order.pullDomainEvents();

        order.release(HCM, "HCM", COORDINATOR, NOW);

        assertThat(order.lines()).extracting(l -> l.sku().code() + " " + l.requestedDeliveryDate())
                .containsExactly("CUP-12OZ-WHITE 2026-12-15", "CUP-12OZ-BLACK null", "LID-90MM null");
    }

    @Test
    @DisplayName("SCRUM-502: only a print line has a requested delivery date")
    void aStockLineHasNoRequestedDeliveryDate() {
        OrderLine stock = Order.line(new Sku("LID-90MM"), 500, Money.vnd(300), null);

        assertThat(stock.requestedDeliveryDate()).isNull();
        assertThat(stock.withRequestedDeliveryDate(null).requestedDeliveryDate()).isNull();
        assertThatThrownBy(() -> stock.withRequestedDeliveryDate(java.time.LocalDate.of(2026, 12, 15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stock.requestedDeliveryDate()).isNull();
    }

    @Test
    @DisplayName("release with nothing to print: READY_TO_FULFILL and OrderReleased")
    void releaseStockOnly() {
        Order order = paidOrder(false);
        order.release(HCM, "HCM", COORDINATOR, NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Released.class,
                e -> assertThat(e.payload().warehouseCode()).isEqualTo("HCM"));
        order.startPicking(COORDINATOR, NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.PICKING);
    }

    @Test
    @DisplayName("an order waiting for its money is not released; a confirmed one is not picked before; released once")
    void releaseRefusals() {
        Order unpaid = draftWithTwoLines();
        unpaid.lines().forEach(line -> unpaid.attachReservations(line.id(), List.of(UUID.randomUUID())));
        unpaid.submit();
        assertThatThrownBy(() -> unpaid.release(HCM, "HCM", COORDINATOR, NOW))
                .isInstanceOf(InvalidOrderTransitionException.class);

        Order printed = paidOrder(true);
        assertThatThrownBy(() -> printed.startPicking(COORDINATOR, NOW))
                .isInstanceOf(InvalidOrderTransitionException.class);

        Order released = paidOrder(false);
        released.release(HCM, "HCM", COORDINATOR, NOW);
        assertThatThrownBy(() -> released.release(HCM, "HCM", COORDINATOR, NOW))
                .isInstanceOf(InvalidOrderTransitionException.class);
    }
}
