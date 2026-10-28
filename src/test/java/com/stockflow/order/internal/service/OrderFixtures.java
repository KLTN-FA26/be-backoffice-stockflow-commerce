package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderNumber;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Builders shared by the order service unit tests: real aggregates in the state a service finds them in. */
final class OrderFixtures {

    static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");
    static final UUID STAFF = UUID.randomUUID();
    static final UUID WAREHOUSE = UUID.randomUUID();

    private OrderFixtures() {
    }

    /** Two blank (stock) lines: 10 cups at 100 000 and 5 lids at 20 000, every line reserved. */
    static Order draft(PaymentTerm term, UUID customerId) {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 27), 7), customerId, UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null)), NOW);
        order.applyTerms(term, term == PaymentTerm.DEPOSIT ? new BigDecimal("30") : null,
                term == PaymentTerm.CREDIT ? 30 : null,
                term == PaymentTerm.DEPOSIT ? com.stockflow.order.api.BalancePolicy.ON_DELIVERY : null);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        return order;
    }

    /** A prepaid order, paid in full, so CONFIRMED and ready to be released. */
    static Order confirmedPrepaid() {
        Order order = draft(PaymentTerm.PREPAID, UUID.randomUUID());
        order.submit();
        order.recordPayment(order.total(), NOW);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** The same, released: READY_TO_FULFILL (no print line). */
    static Order releasedPrepaid() {
        Order order = confirmedPrepaid();
        order.release(WAREHOUSE, "HCM", STAFF, NOW, java.util.Map.of());
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** Prepaid, paid, released, packed, shipped and delivered at {@code deliveredAt}. */
    static Order delivered(Instant deliveredAt) {
        Order order = releasedPrepaid();
        order.markPacked(NOW, STAFF);
        order.markShipped(NOW, STAFF);
        order.markDelivered(deliveredAt, STAFF);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** A CONFIRMED order with one made-to-order (print) line and one blank line, paid in full. */
    static Order confirmedWithPrintLine(UUID design) {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 27), 8), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 500, Money.vnd(2_000), design),
                        Order.line(new Sku("LID-90"), 500, Money.vnd(500), null)), NOW);
        order.applyTerms(PaymentTerm.PREPAID, null);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.submit();
        order.recordPayment(order.total(), NOW);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    static ErrorCode codeOf(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).as("a BusinessException").isNotNull();
        return thrown.errorCode();
    }
}
