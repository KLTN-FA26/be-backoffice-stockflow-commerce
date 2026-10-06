package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import com.stockflow.design.api.DesignService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orders belong to the customer profile, not to the sign-in account. Cancelling "my" order therefore
 * cannot go through the ambient OWN data scope (which compares with the sign-in id and matched no
 * order at all): the service checks the owner explicitly and answers 404 for anyone else's order.
 */
class CancelOwnOrderTest {

    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderServiceImpl service = new OrderServiceImpl(repository, mock(OrderSearchRepository.class),
            mock(InventoryService.class), mock(OrderEventPublisher.class), Clock.systemUTC(),
            mock(DesignService.class), mock(OrderHoldJpaRepository.class), mock(CustomerService.class));

    private Order orderOf(UUID customerId) {
        var order = Order.draft(OrderNumber.of(LocalDate.of(2026, 9, 20), 7), customerId, UUID.randomUUID(),
                List.of(Order.line(new Sku("SOFA-3S-GREY"), 1, Money.vnd(100_000), null)), NOW);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        return order;
    }

    @Test
    void theOwnerCancelsTheirOrder() {
        UUID customer = UUID.randomUUID();
        var order = orderOf(customer);

        service.cancelOwn(order.id().value(), customer, "CUSTOMER_REQUEST");

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(repository).save(order);
    }

    @Test
    void anotherCustomerGetsNotFoundAndNothingChanges() {
        var order = orderOf(UUID.randomUUID());

        assertThatThrownBy(() -> service.cancelOwn(order.id().value(), UUID.randomUUID(), "x"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
        verify(repository, never()).save(any());
    }

    @Test
    void aMissingCustomerIdNeverMatchesAnOrder() {
        var order = orderOf(UUID.randomUUID());

        assertThatThrownBy(() -> service.cancelOwn(order.id().value(), null, "x"))
                .isInstanceOf(BusinessException.class);
    }
}
