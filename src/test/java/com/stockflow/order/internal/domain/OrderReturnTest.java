package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order's side of a return (03 §9, SCRUM-243 / SCRUM-260): RETURN_REQUESTED while a return is open, back to
 * DELIVERED when none gave money back, RETURNED then (PARTIALLY_)REFUNDED when one did; and the free replacement order.
 */
class OrderReturnTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final UUID STAFF = UUID.randomUUID();

    private static Order delivered() {
        Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 4),
                UUID.randomUUID(), UUID.randomUUID(), List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(1_000), null)),
                OrderStatus.DELIVERED, NOW.minus(Duration.ofDays(10)), null, 2L);
        order.restoreLifecycle(null, null, null, NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(2)), null, null,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        order.restorePaymentAndCancellation(BigDecimal.valueOf(10_000), NOW.minus(Duration.ofDays(9)), null, null, null);
        return order;
    }

    @Test
    @DisplayName("a return opened: RETURN_REQUESTED, and it does not complete; a second return changes nothing")
    void requested() {
        Order order = delivered();
        assertThat(order.requestReturn(STAFF, NOW, "RETURN_REQUESTED RMA-1")).isTrue();
        assertThat(order.requestReturn(STAFF, NOW, "RETURN_REQUESTED RMA-2")).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.complete(NOW.plus(Duration.ofDays(30)), Duration.ofDays(7), false)).isFalse();
        assertThat(order.pullTransitions()).singleElement().satisfies(t -> {
            assertThat(t.to()).isEqualTo(OrderStatus.RETURN_REQUESTED);
            assertThat(t.reason()).isEqualTo("RETURN_REQUESTED RMA-1");
        });
    }

    @Test
    @DisplayName("every return ended without money back: DELIVERED again, completing once the window passed")
    void withdrawn() {
        Order order = delivered();
        order.requestReturn(STAFF, NOW, null);
        assertThat(order.withdrawReturn(STAFF, NOW, "RETURN_REJECTED RMA-1")).isTrue();
        assertThat(order.withdrawReturn(STAFF, NOW, "again")).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.complete(NOW.plus(Duration.ofDays(6)), Duration.ofDays(7), false)).isTrue();
    }

    @Test
    @DisplayName("money given back: RETURNED then PARTIALLY_REFUNDED (or REFUNDED), both kept in the history; closed later")
    void settled() {
        Order partly = delivered();
        partly.requestReturn(STAFF, NOW, null);
        partly.pullTransitions();
        assertThat(partly.settleReturn(false, STAFF, NOW, "RETURN_SETTLED RMA-1")).isTrue();
        assertThat(partly.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(partly.refundedAt()).isEqualTo(NOW);
        assertThat(partly.pullTransitions()).extracting(OrderTransition::to)
                .containsExactly(OrderStatus.RETURNED, OrderStatus.PARTIALLY_REFUNDED);
        assertThat(partly.close(NOW.plus(Duration.ofDays(31)), Duration.ofDays(30))).isTrue();

        Order whole = delivered();
        whole.requestReturn(STAFF, NOW, null);
        whole.settleReturn(true, STAFF, NOW, null);
        assertThat(whole.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(delivered().settleReturn(true, STAFF, NOW, null)).as("no return open").isFalse();
    }

    @Test
    @DisplayName("a replacement order costs nothing, is paid in full at once and CONFIRMED, announced as placed")
    void replacement() {
        Order free = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 22), 9), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 3, Money.vnd(0), null)), NOW);
        free.applyTerms(PaymentTerm.PREPAID, null);
        UUID returnId = UUID.randomUUID();
        free.replacesReturn(returnId);
        assertThatThrownBy(() -> free.submitReplacement(NOW)).as("its stock is held first")
                .isInstanceOf(IllegalStateException.class);
        free.lines().forEach(line -> free.attachReservations(line.id(), List.of(UUID.randomUUID())));
        free.submitReplacement(NOW);
        assertThat(free.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(free.paidInFullAt()).isEqualTo(NOW);
        assertThat(free.replacesReturnRequestId()).isEqualTo(returnId);
        assertThat(free.pullDomainEvents()).singleElement().satisfies(event ->
                assertThat(((OrderEvent.Placed) event).payload()).isInstanceOf(OrderPlaced.class));

        Order paid = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 22), 10), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 3, Money.vnd(1_000), null)), NOW);
        assertThatThrownBy(() -> paid.replacesReturn(returnId)).as("never one that costs money")
                .isInstanceOf(IllegalStateException.class);
    }
}
