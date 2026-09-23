package com.stockflow.order.internal.domain;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        order.markPaid();
        order.release();
        order.pullDomainEvents();

        assertThat(order.status().canTransitionTo(OrderStatus.CANCELLED)).isTrue();

        // BR-031: from SHIPPED the goods are with the carrier and the process is a return.
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("a refused cancellation is a 409 CONFLICT carrying the status, not a 400")
    void cancellingFromANonCancellableStatusIsAConflict() {
        Order order = submittedOrder();
        order.cancel("customer changed their mind");

        assertThatThrownBy(() -> order.cancel("again"))
                .isInstanceOf(InvalidOrderTransitionException.class)
                .hasMessageContaining("cannot be cancelled from status CANCELLED")
                .extracting(e -> ((InvalidOrderTransitionException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("paying a cancelled order is a conflict too, and changes nothing")
    void payingACancelledOrderIsAConflict() {
        Order order = submittedOrder();
        order.cancel("changed mind");

        assertThatThrownBy(order::markPaid).isInstanceOf(InvalidOrderTransitionException.class);
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancelling twice is a conflict and keeps the first reason")
    void cancellingTwiceIsAConflict() {
        Order order = submittedOrder();
        order.cancel("first reason");

        assertThatThrownBy(() -> order.cancel("second reason"))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThat(order.cancellationReason()).isEqualTo("first reason");
    }

    @Test
    @DisplayName("cancelling without a reason is refused, not written as null")
    void cancellationRequiresAReason() {
        Order order = submittedOrder();

        // The database has CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL);
        // failing here beats a ConstraintViolationException at flush time.
        assertThatThrownBy(() -> order.cancel("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must record a reason");
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
        assertThat(OrderStatus.PAID.holdsStock()).isTrue();
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
}
