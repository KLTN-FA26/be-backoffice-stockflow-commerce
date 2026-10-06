package com.stockflow.order.internal.domain;

import com.stockflow.common.validation.VietnamPhoneValidator;

/** Immutable postal values copied at checkout so later profile edits cannot alter an order. */
public record OrderAddressSnapshot(
        String recipientName, String phone, String line1, String line2,
        String wardCode, String wardName, String provinceCode, String provinceName,
        String countryCode, String postalCode
) {
    public OrderAddressSnapshot {
        recipientName = required(recipientName, "recipientName");
        phone = VietnamPhoneValidator.normalise(required(phone, "phone"));
        line1 = required(line1, "line1");
        wardCode = required(wardCode, "wardCode");
        wardName = required(wardName, "wardName");
        provinceCode = required(provinceCode, "provinceCode");
        provinceName = required(provinceName, "provinceName");
        countryCode = required(countryCode, "countryCode").toUpperCase(java.util.Locale.ROOT);
        if (!"VN".equals(countryCode)) throw new IllegalArgumentException("Only VN addresses are supported");
        line2 = optional(line2);
        postalCode = optional(postalCode);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
