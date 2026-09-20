package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CheckoutCustomer;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.order.api.PlaceGuestOrderCommand;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import org.junit.jupiter.api.Test;

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

class GuestCheckoutServiceTest {

    @Test
    void accountCheckoutResolvesSavedAddressesAndFreezesThemOnTheOrder() {
        OrderRepository orders = mock(OrderRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        OrderEventPublisher events = mock(OrderEventPublisher.class);
        CustomerService customers = mock(CustomerService.class);
        Instant now = Instant.parse("2026-09-18T10:00:00Z");
        UUID customerId = UUID.randomUUID();
        UUID shippingId = UUID.randomUUID();
        var address = new CheckoutCustomer.CheckoutAddress("Minh", "0901234567",
                "12 Nguyen Hue", null, "26734", "Ben Nghe", "79",
                "Ho Chi Minh City", "VN", null);
        when(orders.findByRequestId(any())).thenReturn(Optional.empty());
        when(orders.nextOrderNumber(LocalDate.of(2026, 9, 18)))
                .thenReturn(OrderNumber.of(LocalDate.of(2026, 9, 18), 1));
        when(inventory.reserve(any())).thenReturn(new ReserveStockResult(UUID.randomUUID(),
                List.of(new com.stockflow.inventory.api.StockReservation(UUID.randomUUID(),
                        UUID.randomUUID(), "A-01", null, 1)), 1, 0, now));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        when(customers.resolveCheckout(customerId, shippingId, null))
                .thenReturn(new CheckoutCustomer(customerId, "Minh", "minh@example.com",
                        "0901234567", address, address));
        var service = new OrderServiceImpl(orders, mock(com.stockflow.order.internal.repository.OrderSearchRepository.class), inventory, events,
                Clock.fixed(now, ZoneOffset.UTC), mock(DesignService.class),
                mock(OrderHoldJpaRepository.class), customers);

        var result = service.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customerId,
                shippingId, null, true,
                List.of(new PlaceOrderCommand.Line(new Sku("TABLE-OAK"), 1,
                        Money.vnd(100), null))));

        assertThat(result.customerId()).isEqualTo(customerId);
        assertThat(result.contactEmail()).isEqualTo("minh@example.com");
        assertThat(result.shippingAddress().line1()).isEqualTo("12 Nguyen Hue");
        verify(customers).resolveCheckout(customerId, shippingId, null);
        verify(orders).save(any(Order.class));
    }

    @Test
    void guestCheckoutReservesStockAndPersistsAddressSnapshotsWithoutCustomerAccount() {
        OrderRepository orders = mock(OrderRepository.class);
        InventoryService inventory = mock(InventoryService.class);
        OrderEventPublisher events = mock(OrderEventPublisher.class);
        Instant now = Instant.parse("2026-09-18T10:00:00Z");
        when(orders.findByRequestId(any())).thenReturn(Optional.empty());
        when(orders.nextOrderNumber(LocalDate.of(2026, 9, 18)))
                .thenReturn(OrderNumber.of(LocalDate.of(2026, 9, 18), 1));
        when(inventory.reserve(any())).thenReturn(new ReserveStockResult(UUID.randomUUID(),
                List.of(new com.stockflow.inventory.api.StockReservation(UUID.randomUUID(),
                        UUID.randomUUID(), "A-01", null, 1)), 1, 0, now));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        var service = new OrderServiceImpl(orders, mock(com.stockflow.order.internal.repository.OrderSearchRepository.class), inventory, events,
                Clock.fixed(now, ZoneOffset.UTC), mock(DesignService.class),
                mock(OrderHoldJpaRepository.class), mock(com.stockflow.customer.api.CustomerService.class));
        var address = new PlaceGuestOrderCommand.Address("Minh", "0901234567", "12 Nguyen Hue",
                null, "26734", "Ben Nghe", "79", "Ho Chi Minh City", "VN", null);

        var result = service.placeGuestOrder(new PlaceGuestOrderCommand(UUID.randomUUID(),
                "MINH@EXAMPLE.COM", address, address,
                List.of(new PlaceGuestOrderCommand.Line(new Sku("TABLE-OAK"), 1, Money.vnd(100)))));

        assertThat(result.customerId()).isNull();
        assertThat(result.contactEmail()).isEqualTo("minh@example.com");
        assertThat(result.shippingAddress().provinceName()).isEqualTo("Ho Chi Minh City");
        verify(inventory).reserve(any());
        verify(orders).save(any(Order.class));
        verify(events).publishEventsOf(any(Order.class));
    }

    @Test
    void guestCheckoutRejectsAChangedPayloadReusingAnExistingRequestId() {
        OrderRepository orders = mock(OrderRepository.class);
        UUID requestId = UUID.randomUUID();
        var address = new PlaceGuestOrderCommand.Address("Minh", "0901234567", "12 Nguyen Hue",
                null, "26734", "Ben Nghe", "79", "Ho Chi Minh City", "VN", null);
        Order existing = Order.guestDraft(OrderNumber.of(LocalDate.of(2026, 9, 18), 1), requestId,
                List.of(Order.line(new Sku("TABLE-OAK"), 1, Money.vnd(100), null)),
                Instant.parse("2026-09-18T10:00:00Z"), "Minh", "minh@example.com", "+84901234567",
                new com.stockflow.order.internal.domain.OrderAddressSnapshot("Minh", "0901234567",
                        "12 Nguyen Hue", null, "26734", "Ben Nghe", "79", "Ho Chi Minh City", "VN", null),
                new com.stockflow.order.internal.domain.OrderAddressSnapshot("Minh", "0901234567",
                        "12 Nguyen Hue", null, "26734", "Ben Nghe", "79", "Ho Chi Minh City", "VN", null));
        when(orders.findByRequestId(requestId)).thenReturn(Optional.of(existing));
        var service = new OrderServiceImpl(orders, mock(com.stockflow.order.internal.repository.OrderSearchRepository.class), mock(InventoryService.class),
                mock(OrderEventPublisher.class), Clock.systemUTC(), mock(DesignService.class),
                mock(OrderHoldJpaRepository.class), mock(CustomerService.class));

        assertThatThrownBy(() -> service.placeGuestOrder(new PlaceGuestOrderCommand(requestId,
                "minh@example.com", address, address,
                List.of(new PlaceGuestOrderCommand.Line(new Sku("TABLE-OAK"), 2, Money.vnd(100))))))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
}
