package com.stockflow.customer.api;

/** A completed registration, including the first bearer token. */
public record CustomerRegistration(
        CustomerSummary customer,
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}
