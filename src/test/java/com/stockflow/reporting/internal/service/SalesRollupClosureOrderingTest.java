package com.stockflow.reporting.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.reporting.internal.domain.OrderContribution;
import com.stockflow.reporting.internal.domain.OrderEventType;
import com.stockflow.reporting.internal.domain.SalesRollupLedger;
import com.stockflow.reporting.internal.repository.SalesRollupLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A short completion whose {@code OrderPlaced} reporting has not applied yet (the two events are handled apart): the
 * closure counts the order first, as a cancellation does, and the placement that follows finds it recorded.
 */
class SalesRollupClosureOrderingTest {

    private static final Instant PLACED_AT = Instant.parse("2026-11-02T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    /** What the SQL ledger does, over maps: what was applied, and the effect of each call on the day's revenue. */
    private static final class FakeLedger implements SalesRollupLedger {
        final Map<String, OrderContribution> applied = new HashMap<>();
        final Map<UUID, OrderContribution> closures = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        BigDecimal revenue = BigDecimal.ZERO;
        int orders;

        @Override public boolean markApplied(UUID orderId, OrderEventType type, OrderContribution contribution) {
            calls.add("mark " + type);
            return applied.putIfAbsent(orderId + type.name(), contribution) == null;
        }
        @Override public Optional<OrderContribution> placedContribution(UUID orderId) {
            return Optional.ofNullable(applied.get(orderId + "PLACED"));
        }
        @Override public void apply(OrderContribution contribution, int sign) {
            calls.add("apply " + sign);
            orders += sign;
            revenue = revenue.add(contribution.total().multiply(BigDecimal.valueOf(sign)));
        }
        @Override public void clearDay(LocalDate day) { }
        @Override public boolean markReturnApplied(UUID returnRequestId, String event, OrderContribution taken) {
            return true;
        }
        @Override public void applyReturn(OrderContribution taken) {
            calls.add("take off");
            revenue = revenue.subtract(taken.total());
        }
        @Override public List<OrderContribution> returnsOf(LocalDate day) { return List.of(); }
        @Override public boolean markClosureApplied(UUID closureId, OrderContribution taken) {
            calls.add("mark closure");
            return closures.putIfAbsent(closureId, taken) == null;
        }
        @Override public List<OrderContribution> closuresOfOrder(UUID orderId) { return List.copyOf(closures.values()); }
        @Override public List<OrderContribution> closuresOf(LocalDate day) { return List.copyOf(closures.values()); }
        @Override public boolean cancellationApplied(UUID orderId) { return applied.containsKey(orderId + "CANCELLED"); }
    }

    private final OrderService orders = mock(OrderService.class);
    private final FakeLedger ledger = new FakeLedger();
    private final SalesRollupService service = new SalesRollupService(orders, ledger, mock(SalesRollupLock.class));

    private OrderSummary order(UUID id) {
        return new OrderSummary(id, "SO-1", UUID.randomUUID(), OrderStatus.IN_PRODUCTION, Money.vnd(100_000),
                List.of(new OrderSummary.LineSummary(UUID.randomUUID(), "CUP", 10, Money.vnd(10_000),
                        Money.vnd(100_000), List.of())), PLACED_AT);
    }

    private static OrderRemainderClosed shortfall(UUID orderId) {
        return new OrderRemainderClosed(orderId, "SO-1", UUID.randomUUID(), OrderRemainderClosed.PRODUCTION_SHORTFALL,
                List.of(new OrderRemainderClosed.Line(UUID.randomUUID(), "CUP", 4)), new BigDecimal("40000"),
                new BigDecimal("60000"), BigDecimal.ZERO, BigDecimal.ZERO, "VND", UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a closure before OrderPlaced counts the order first, then takes the closed part off; the OrderPlaced "
            + "that follows finds it recorded and adds nothing")
    void closureBeforePlacement() {
        UUID id = UUID.randomUUID();
        when(orders.findById(id)).thenReturn(Optional.of(order(id)));

        service.productionShortfallClosed(shortfall(id));

        assertThat(ledger.calls).containsExactly("mark PLACED", "apply 1", "mark closure", "take off");
        assertThat(ledger.orders).isEqualTo(1);
        assertThat(ledger.revenue).isEqualByComparingTo("60000");
        assertThat(ledger.closures.values()).singleElement().satisfies(taken -> {
            assertThat(taken.day()).isEqualTo(DAY);
            assertThat(taken.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(4));
        });

        service.orderPlaced(id);

        assertThat(ledger.orders).as("counted once").isEqualTo(1);
        assertThat(ledger.revenue).isEqualByComparingTo("60000");
    }

    @Test
    @DisplayName("a closure after OrderPlaced does not read the order again")
    void closureAfterPlacement() {
        UUID id = UUID.randomUUID();
        when(orders.findById(id)).thenReturn(Optional.of(order(id)));
        service.orderPlaced(id);

        service.productionShortfallClosed(shortfall(id));

        assertThat(ledger.orders).isEqualTo(1);
        assertThat(ledger.revenue).isEqualByComparingTo("60000");
        verify(orders, org.mockito.Mockito.times(1)).findById(id);
    }

    @Test
    @DisplayName("a closure of an order that cannot be counted (unknown, a free replacement) is left out, nothing recorded")
    void closureOfAnUncountableOrder() {
        UUID id = UUID.randomUUID();
        when(orders.findById(id)).thenReturn(Optional.empty());

        service.productionShortfallClosed(shortfall(id));

        assertThat(ledger.calls).isEmpty();
        assertThat(ledger.revenue).isEqualByComparingTo("0");
    }
}
