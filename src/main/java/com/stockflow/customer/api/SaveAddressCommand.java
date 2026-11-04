package com.stockflow.customer.api;

/** Vietnam's post-merger two-level address: province/city, then ward/commune/special zone. */
public record SaveAddressCommand(
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
        Long expectedVersion
) {
}
