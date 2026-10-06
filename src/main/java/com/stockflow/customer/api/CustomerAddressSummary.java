package com.stockflow.customer.api;

import java.util.UUID;

public record CustomerAddressSummary(
        UUID addressId,
        UUID customerId,
        String type,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String wardCode,
        String wardName,
        String provinceCode,
        String provinceName,
        String countryCode,
        String postalCode,
        boolean defaultAddress,
        long version
) {
}
