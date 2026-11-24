package com.stockflow.order.internal.service;

import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import com.stockflow.order.internal.repository.OrderRemainderClosureJpaRepository;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-37 (L-I7): an order DELIVERED by closing its rest follows its open returns exactly as one DELIVERED by a
 * delivery does ({@code OrderDeliveredReturnsListener}): {@code ReturnRequestService.followDelivery} is asked, so a
 * return opened while it was partly fulfilled takes it to RETURN_REQUESTED, rather than leaving it DELIVERED with a
 * claim open - completable over the claim.
 */
class CloseRestFollowsReturnsTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final ReturnRequestService returns = mock(ReturnRequestService.class);
    private final OrderServiceImpl service = new OrderServiceImpl(repository, mock(OrderSearchRepository.class),
            mock(InventoryService.class), mock(OrderEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC),
            mock(DesignService.class), mock(OrderHoldJpaRepository.class), mock(CustomerService.class),
            mock(CancellationRequestRepository.class), paymentService,
            mock(com.stockflow.product.api.CustomerSkus.class),
            mock(com.stockflow.order.internal.domain.SalesContractRepository.class), mock(DeliveryBatchService.class),
            mock(com.stockflow.order.internal.domain.DeliveryBatchRecords.class),
            mock(com.stockflow.order.internal.domain.DeliveryFailureCases.class), mock(ObjectProvider.class),
            mock(OrderRemainderClosureJpaRepository.class));

    {
        when(paymentService.installmentStanding(any())).thenAnswer(call -> com.stockflow.payment.api.InstallmentStanding
                .none(call.getArgument(0)));
        @SuppressWarnings("unchecked")
        ObjectProvider<ReturnRequestService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(returns);
        service.returnRequests(provider);
    }

    /** Released, split shipments allowed, picking; the cups (10) handed over, the lids (5) not. */
    private Order cupsHandedOver() {
        Order order = OrderFixtures.released();
        order.decidePartialShipment(true);
        order.startPicking(STAFF, NOW);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(repository.findById(order.id())).thenReturn(Optional.of(order));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        UUID shipment = UUID.randomUUID();
        service.recordHandover(order.id().value(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 10)), NOW, STAFF));
        order.pullDomainEvents();
        return order;
    }

    @Test
    @DisplayName("the cups delivered, the lids closed: DELIVERED by the closure, and the returns are followed")
    void deliveredByTheClosure() {
        Order order = cupsHandedOver();
        UUID shipment = order.shipments().getFirst().id();
        service.recordShipmentDelivered(order.id().value(), shipment, STAFF);
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        OrderSummary closed = service.closeRemainder(order.id().value(), CancellationReasonCode.OUT_OF_STOCK,
                "no lids", STAFF);

        assertThat(closed.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(returns).followDelivery(order.id().value(), STAFF);
    }

    @Test
    @DisplayName("the cups still with the carrier, the lids closed: SHIPPED, not delivered - no return is followed")
    void notDeliveredByTheClosure() {
        Order order = cupsHandedOver();

        OrderSummary closed = service.closeRemainder(order.id().value(), CancellationReasonCode.OUT_OF_STOCK,
                "no lids", STAFF);

        assertThat(closed.status()).isEqualTo(OrderStatus.SHIPPED);
        verify(returns, never()).followDelivery(any(), any());
    }
}
