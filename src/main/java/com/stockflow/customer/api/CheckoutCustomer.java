package com.stockflow.customer.api;

import java.util.UUID;

/** Stable customer and address values to snapshot onto an order. */
public record CheckoutCustomer(
        UUID customerId,
        String fullName,
        String email,
        String phone,
        CheckoutAddress shippingAddress,
        CheckoutAddress billingAddress
) {
    public record CheckoutAddress(
            String recipientName,
            String phone,
            String line1,
            String line2,
            String wardCode,
            String wardName,
            String provinceCode,
            String provinceName,
            String countryCode,
            String postalCode
    ) {
    }
}
