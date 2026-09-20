package com.stockflow.customer.internal.controller.dto;

public record CustomerRegistrationResponse(
        CustomerResponse customer,
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}
