package com.stockflow.order.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.controller.dto.PlaceGuestOrderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Guest checkout lets the caller name the unit price and nothing checks it against a catalogue price
 * yet, so the public endpoint is refused unless it was switched on on purpose.
 */
class GuestCheckoutControllerTest {

    private static PlaceGuestOrderRequest request() {
        var address = new PlaceGuestOrderRequest.Address("Khach", "0901234567", "5 Pasteur", null,
                "26734", "Ben Nghe", "79", "HCM", "VN", null);
        return new PlaceGuestOrderRequest(UUID.randomUUID(), "guest@example.com", address, true, null,
                List.of(new PlaceGuestOrderRequest.Line("SKU", 1, new BigDecimal("1"))));
    }

    @Test
    void isRefusedByDefaultWithoutTouchingStockOrOrders() {
        var orders = mock(OrderService.class);
        var controller = new GuestCheckoutController(orders, false);

        assertThatThrownBy(() -> controller.place(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.GUEST_CHECKOUT_DISABLED));
        verifyNoInteractions(orders);
    }
}
