package com.stockflow.customer.internal.controller.dto;

import java.util.UUID;

public record CustomerAddressResponse(
        UUID addressId, UUID customerId, String type, String recipientName, String phone,
        String line1, String line2, String wardCode, String wardName, String provinceCode,
        String provinceName, String countryCode, String postalCode, boolean defaultAddress,
        long version
) {
}
