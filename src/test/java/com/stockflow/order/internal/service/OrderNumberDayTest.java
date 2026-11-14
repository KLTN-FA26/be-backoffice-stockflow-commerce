package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.DeliveryBatchRecords;
import com.stockflow.order.internal.domain.DeliveryFailureCases;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.product.api.CustomerSkus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A7: an order's number carries the day in the business's calendar (Asia/Ho_Chi_Minh), as a quote's does - not the UTC
 * day, which is the day before from midnight to 07:00 in Vietnam.
 */
class OrderNumberDayTest {

    /** 01:30 on 14 November in Ho Chi Minh City; still 13 November in UTC. */
    private static final Instant EARLY_MORNING = Instant.parse("2026-11-13T18:30:00Z");

    @Test
    @DisplayName("converted at 01:30 in Vietnam, the order is numbered for that day, not for the UTC day before")
    void numberedForTheBusinessDay() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderServiceImpl service = new OrderServiceImpl(repository, mock(OrderSearchRepository.class),
                mock(InventoryService.class), mock(OrderEventPublisher.class), Clock.fixed(EARLY_MORNING,
                ZoneOffset.UTC), mock(DesignService.class), mock(OrderHoldJpaRepository.class),
                mock(CustomerService.class), mock(CancellationRequestRepository.class), mock(PaymentService.class),
                mock(CustomerSkus.class), mock(SalesContractRepository.class), mock(DeliveryBatchService.class),
                mock(DeliveryBatchRecords.class), mock(DeliveryFailureCases.class));
        when(repository.findByRequestId(any())).thenReturn(Optional.empty());
        IllegalStateException stop = new IllegalStateException("numbered");
        when(repository.nextOrderNumber(any())).thenThrow(stop);

        assertThatThrownBy(() -> service.placeQuotedOrder(new PlaceOrderCommand(UUID.randomUUID(), UUID.randomUUID(),
                null, null, false, List.of(new PlaceOrderCommand.Line(new Sku("LY-A"), 10, Money.vnd(1_000), null)),
                List.of()))).isSameAs(stop);

        ArgumentCaptor<LocalDate> day = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).nextOrderNumber(day.capture());
        assertThat(day.getValue()).isEqualTo(LocalDate.of(2026, 11, 14));
    }
}
