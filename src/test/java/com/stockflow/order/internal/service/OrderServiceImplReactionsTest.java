package com.stockflow.order.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@link OrderServiceImpl} does when other modules tell it something: an expired stock hold (14 BR-07), and what any
 * cancellation does with the order's holds (17 §4.4, BR-PRD-10). The order counts no money since 4B-10 - a receipt
 * confirms it through its installments, covered with them. {@code placeOrder} is deliberately not covered here.
 */
class OrderServiceImplReactionsTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final CancellationRequestRepository requests = mock(CancellationRequestRepository.class);
    private final OrderServiceImpl service = new OrderServiceImpl(repository, mock(OrderSearchRepository.class),
            inventory, events, Clock.fixed(NOW, ZoneOffset.UTC), mock(DesignService.class),
            mock(OrderHoldJpaRepository.class), mock(CustomerService.class), requests,
            mock(com.stockflow.payment.api.PaymentService.class), org.mockito.Mockito.mock(com.stockflow.product.api.CustomerSkus.class), org.mockito.Mockito.mock(com.stockflow.order.internal.domain.SalesContractRepository.class));

    private Order found(Order order) {
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        return order;
    }

    /** Its contract signed with an installment due on signing, not paid yet: PENDING_PAYMENT. */
    private Order waitingForPayment() {
        Order order = OrderFixtures.draft(UUID.randomUUID());
        order.sendContract(STAFF, NOW);
        order.contractSigned(false, true, STAFF, NOW);
        order.pullDomainEvents();
        return found(order);
    }

    private UUID id(Order order) {
        return order.id().value();
    }

    @Nested
    @DisplayName("what cancelling does with the holds")
    class Cancelling {

        @Test
        @DisplayName("every reservation of a cancelled order is released as ORDER_CANCELLED")
        void releasesEveryHold() {
            Order order = waitingForPayment();
            List<UUID> holds = order.reservationIds();
            assertThat(holds).hasSize(2);

            service.cancelLoaded(order, new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                    STAFF));

            holds.forEach(hold -> verify(inventory).release(hold, "ORDER_CANCELLED"));
            assertThat(order.reservationIds()).isEmpty();
            verify(events).publishEventsOf(order);
        }

        @Test
        @DisplayName("an order already shipped is refused: ORDER_NOT_CANCELLABLE, nothing released or saved")
        void aShippedOrderIsNotCancelled() {
            Order order = OrderFixtures.released();
            order.markPacked(NOW, STAFF);
            order.markShipped(NOW, STAFF);

            assertThat(OrderFixtures.codeOf(() -> service.cancelLoaded(order,
                    new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, null, null, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
            verify(inventory, never()).release(any(), anyString());
            verify(repository, never()).save(any());
        }
    }
}
