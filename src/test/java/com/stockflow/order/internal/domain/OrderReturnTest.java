package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order's side of a return (03 §9, SCRUM-243 / SCRUM-260): RETURN_REQUESTED while a return is open, back to
 * DELIVERED when none brought goods back, RETURNED once goods are back (4D-3: a return receipt), then (PARTIALLY_)REFUNDED
 * once the accountant refunded what the settlements made refundable. There is no replacement order any more.
 */
class OrderReturnTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final UUID STAFF = UUID.randomUUID();

    private static Order delivered() {
        Order order = new Order(new OrderId(UUID.randomUUID()), OrderNumber.of(LocalDate.of(2026, 10, 15), 4),
                UUID.randomUUID(), UUID.randomUUID(), List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(1_000), null)),
                OrderStatus.DELIVERED, NOW.minus(Duration.ofDays(10)), null, 2L);
        order.restoreLifecycle(null, null, null, NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(2)), null, null,
                null, null);
        return order;
    }

    @Test
    @DisplayName("a return opened: RETURN_REQUESTED, and it does not complete; a second return changes nothing")
    void requested() {
        Order order = delivered();
        assertThat(order.requestReturn(STAFF, NOW, "RETURN_REQUESTED RMA-1")).isTrue();
        assertThat(order.requestReturn(STAFF, NOW, "RETURN_REQUESTED RMA-2")).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.complete(NOW.plus(Duration.ofDays(30)), Duration.ofDays(7), false, false)).isFalse();
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
        assertThat(order.complete(NOW.plus(Duration.ofDays(6)), Duration.ofDays(7), false, false)).isTrue();
    }

    @Test
    @DisplayName("the goods back: RETURN_REQUESTED -> RETURNED once (a second return's goods change nothing)")
    void goodsBack() {
        Order order = delivered();
        assertThat(order.goodsReturned(STAFF, NOW, "x")).as("no return open").isFalse();
        order.requestReturn(STAFF, NOW, null);
        order.pullTransitions();
        assertThat(order.goodsReturned(STAFF, NOW, "GOODS_RETURNED RMA-1")).isTrue();
        assertThat(order.goodsReturned(STAFF, NOW, "GOODS_RETURNED RMA-2")).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
        assertThat(order.pullTransitions()).singleElement().satisfies(t -> {
            assertThat(t.to()).isEqualTo(OrderStatus.RETURNED);
            assertThat(t.reason()).isEqualTo("GOODS_RETURNED RMA-1");
        });
        assertThat(order.complete(NOW.plus(Duration.ofDays(30)), Duration.ofDays(7), false, false))
                .as("a returned order never completes").isFalse();
    }

    @Test
    @DisplayName("refunded: RETURNED -> REFUNDED when it covers the order, PARTIALLY_REFUNDED otherwise; closed later")
    void refunded() {
        Order partly = delivered();
        partly.requestReturn(STAFF, NOW, null);
        assertThat(partly.refunded(false, STAFF, NOW, "x")).as("goods not back yet").isFalse();
        partly.goodsReturned(STAFF, NOW, null);
        partly.pullTransitions();
        assertThat(partly.refunded(false, STAFF, NOW, "REFUNDED RMA-1")).isTrue();
        assertThat(partly.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(partly.refundedAt()).isEqualTo(NOW);
        assertThat(partly.pullTransitions()).extracting(OrderTransition::to)
                .containsExactly(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(partly.refunded(true, STAFF, NOW, "again")).as("once").isFalse();
        assertThat(partly.close(NOW.plus(Duration.ofDays(31)), Duration.ofDays(30), false)).isTrue();

        Order whole = delivered();
        whole.requestReturn(STAFF, NOW, null);
        whole.goodsReturned(STAFF, NOW, null);
        whole.refunded(true, STAFF, NOW, null);
        assertThat(whole.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(delivered().refunded(true, STAFF, NOW, null)).as("no return, nothing to refund").isFalse();
    }

    @Test
    @DisplayName("a further claim on an order a return refunded reopens it: RETURN_REQUESTED, then RETURNED again")
    void furtherClaim() {
        Order order = delivered();
        order.requestReturn(STAFF, NOW, null);
        order.goodsReturned(STAFF, NOW, null);
        order.refunded(false, STAFF, NOW, null);
        assertThat(order.requestReturn(STAFF, NOW, "RETURN_REQUESTED RMA-2")).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.goodsReturned(STAFF, NOW, null)).isTrue();
        assertThat(order.refunded(true, STAFF, NOW, null)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
    }
}
