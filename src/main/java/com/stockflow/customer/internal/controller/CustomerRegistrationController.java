package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.internal.controller.dto.CustomerRegistrationResponse;
import com.stockflow.customer.internal.controller.dto.RegisterCustomerRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/registrations")
class CustomerRegistrationController {
    private final CustomerService customers;

    CustomerRegistrationController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit(limit = 5, perSeconds = 60, key = RateLimit.Key.IP)
    public ApiResponse<CustomerRegistrationResponse> register(
            @Valid @RequestBody RegisterCustomerRequest request) {
        return ApiResponse.ok(CustomerWebMapper.toResponse(customers.register(CustomerWebMapper.toCommand(request))));
    }
}
