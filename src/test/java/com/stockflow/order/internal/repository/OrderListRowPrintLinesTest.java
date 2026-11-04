package com.stockflow.order.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Review of #150, item 8: a list row's printLinesReady, without the order's lines. */
class OrderListRowPrintLinesTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

    private static Order inProduction() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 22), 3), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("HOP"), 2, Money.vnd(100_000), null),
                        Order.line(new Sku("LY-IN"), 5, Money.vnd(50_000), UUID.randomUUID())), NOW);
        order.applyTerms(PaymentTerm.PREPAID, null);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();
        order.recordPayment(order.total(), NOW);
        order.release(UUID.randomUUID(), "HCM", UUID.randomUUID(), NOW, Map.of());
        return order;
    }

    @Test
    @DisplayName("IN_PRODUCTION, and ON_HOLD held from IN_PRODUCTION, are not ready; READY_TO_FULFILL is")
    void heldFromProductionIsNotReady() {
        Order printing = inProduction();
        assertThat(OrderPersistenceMapper.toListSummary(OrderPersistenceMapper.toNewEntity(printing)).lifecycle()
                .printLinesReady()).isFalse();

        Order held = inProduction();
        held.putOnHold(HoldReason.CUSTOMER, null, UUID.randomUUID(), NOW);
        assertThat(held.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(OrderPersistenceMapper.toListSummary(OrderPersistenceMapper.toNewEntity(held)).lifecycle()
                .printLinesReady()).isFalse();

        Order done = inProduction();
        done.completeProduction(Map.of(done.lines().get(1).id(), 5), "HCM", NOW);
        assertThat(OrderPersistenceMapper.toListSummary(OrderPersistenceMapper.toNewEntity(done)).lifecycle()
                .printLinesReady()).isTrue();
    }
}
