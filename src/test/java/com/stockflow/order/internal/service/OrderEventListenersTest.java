package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.payment.api.InstallmentOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The order side of the events inventory publishes (kltn-docs 14 BR-07), and what it tells payment about the order of an
 * installment. Payment no longer publishes events the order counts money from (4B-10): its receipts confirm an order
 * through {@code InstallmentReceiptListener}, tested with the installments.
 */
class OrderEventListenersTest {

    private final OrderServiceImpl orders = mock(OrderServiceImpl.class);

    @Nested
    @DisplayName("what the order tells payment about the order of an installment")
    class Installments {

        private final OrderRepository repository = mock(OrderRepository.class);
        private final InstallmentOrdersAdapter adapter = new InstallmentOrdersAdapter(repository, mock(DeliveryBatchService.class));

        @Test
        @DisplayName("a confirmed order reports whose it is, its status, its currency and that it may be held")
        void confirmedOrder() {
            Order order = OrderFixtures.confirmed();
            when(repository.findById(order.id())).thenReturn(Optional.of(order));

            InstallmentOrder found = adapter.find(order.id().value()).orElseThrow();

            assertThat(found.orderId()).isEqualTo(order.id().value());
            assertThat(found.orderNumber()).isEqualTo(order.orderNumber().value());
            assertThat(found.customerId()).isEqualTo(order.customerId());
            assertThat(found.status()).isEqualTo(OrderStatus.CONFIRMED.name());
            assertThat(found.currency()).isEqualTo("VND");
            assertThat(found.holdable()).isTrue();
        }

        @Test
        @DisplayName("a cancelled order cannot be held")
        void cancelledOrderIsNotHoldable() {
            Order order = OrderFixtures.draft(UUID.randomUUID());
            order.cancel(com.stockflow.order.api.CancellationReasonCode.CUSTOMER_REQUEST, null, null, null, NOW);
            when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));

            InstallmentOrder found = adapter.lockForPayment(order.id().value()).orElseThrow();

            assertThat(found.status()).isEqualTo("CANCELLED");
            assertThat(found.holdable()).isFalse();
        }

        @Test
        @DisplayName("delivered in full is known only once every unit was delivered, or the rest closed after the last")
        void deliveredInFull() {
            Order released = OrderFixtures.released();
            Order delivered = OrderFixtures.delivered(NOW);
            when(repository.findById(released.id())).thenReturn(Optional.of(released));
            when(repository.findById(delivered.id())).thenReturn(Optional.of(delivered));

            assertThat(adapter.deliveredInFullAt(released.id().value())).isEmpty();
            assertThat(adapter.deliveredInFullAt(delivered.id().value())).contains(NOW);
        }

        @Test
        @DisplayName("an unknown order is empty, never an exception")
        void unknown() {
            when(repository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

            assertThat(adapter.find(UUID.randomUUID())).isEmpty();
            verify(repository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
        }
    }
}
