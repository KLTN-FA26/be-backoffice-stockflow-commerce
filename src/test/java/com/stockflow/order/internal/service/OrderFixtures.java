package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderNumber;

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
    static Order draft(UUID customerId) {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 27), 7), customerId, UUID.randomUUID(), List.of(
                Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null),
                Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        return order;
    }

    /** Its contract signed with nothing due on signing (14 BR-15, 15 BR-07): CONFIRMED, shipping from WAREHOUSE. */
    static void confirm(Order order) {
        order.sendContract(STAFF, NOW);
        order.contractSigned(false, false, STAFF, NOW);
        order.chooseShippingWarehouse(WAREHOUSE, true, STAFF, NOW);
    }

    /** A CONFIRMED order of a signed contract, ready to be released. */
    static Order confirmed() {
        Order order = draft(UUID.randomUUID());
        confirm(order);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** The same, released: READY_TO_FULFILL (no print line). */
    static Order released() {
        Order order = confirmed();
        order.release(WAREHOUSE, "HCM", STAFF, NOW);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** Confirmed, released, packed, shipped and delivered at {@code deliveredAt}. */
    static Order delivered(Instant deliveredAt) {
        Order order = released();
        order.markPacked(NOW, STAFF);
        order.markShipped(NOW, STAFF);
        order.markDelivered(deliveredAt, STAFF);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** A CONFIRMED order with one made-to-order (print) line and one blank line. */
    static Order confirmedWithPrintLine(UUID design) {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 27), 8), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 500, Money.vnd(2_000), design),
                        Order.line(new Sku("LID-90"), 500, Money.vnd(500), null)), NOW);
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        confirm(order);
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** The summary the order module publishes for {@code order}, built by the real mapping. */
    static com.stockflow.order.api.OrderSummary summaryOf(Order order) {
        com.stockflow.order.internal.domain.OrderRepository repository =
                org.mockito.Mockito.mock(com.stockflow.order.internal.domain.OrderRepository.class);
        org.mockito.Mockito.when(repository.findById(order.id())).thenReturn(java.util.Optional.of(order));
        OrderServiceImpl service = new OrderServiceImpl(repository,
                org.mockito.Mockito.mock(com.stockflow.order.internal.repository.OrderSearchRepository.class),
                org.mockito.Mockito.mock(com.stockflow.inventory.api.InventoryService.class),
                org.mockito.Mockito.mock(OrderEventPublisher.class), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                org.mockito.Mockito.mock(com.stockflow.design.api.DesignService.class),
                org.mockito.Mockito.mock(com.stockflow.order.internal.repository.OrderHoldJpaRepository.class),
                org.mockito.Mockito.mock(com.stockflow.customer.api.CustomerService.class),
                org.mockito.Mockito.mock(com.stockflow.order.internal.domain.CancellationRequestRepository.class),
                org.mockito.Mockito.mock(com.stockflow.payment.api.PaymentService.class), org.mockito.Mockito.mock(com.stockflow.product.api.CustomerSkus.class), org.mockito.Mockito.mock(com.stockflow.order.internal.domain.SalesContractRepository.class));
        return service.findById(order.id().value()).orElseThrow();
    }

    static ErrorCode codeOf(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).as("a BusinessException").isNotNull();
        return thrown.errorCode();
    }
}
