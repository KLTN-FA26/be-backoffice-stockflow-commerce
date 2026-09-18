package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.PlaceGuestOrderRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public B2C checkout. Custom-design lines remain account-only and are absent from this contract. */
@RestController
@RequestMapping("/api/v1/orders/guest-checkout")
class GuestCheckoutController {
    private final OrderService orders;

    GuestCheckoutController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit(limit = 10, perSeconds = 60, key = RateLimit.Key.IP)
    public ApiResponse<OrderResponse> place(@Valid @RequestBody PlaceGuestOrderRequest request) {
        if (!request.billingSameAsShipping() && request.billingAddress() == null) {
            throw new com.stockflow.common.error.BusinessException(
                    com.stockflow.common.error.ErrorCode.VALIDATION_FAILED,
                    "billingAddress is required when billingSameAsShipping is false");
        }
        return ApiResponse.ok(OrderWebMapper.toResponse(orders.placeGuestOrder(
                OrderWebMapper.toCommand(request))));
    }
}
