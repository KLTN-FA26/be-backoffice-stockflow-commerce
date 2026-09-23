package com.stockflow.customer.internal.domain;

import com.stockflow.common.validation.VietnamPhoneValidator;

import java.util.Objects;
import java.util.UUID;

/** Immutable post-merger Vietnamese address value owned by the Customer aggregate. */
public record CustomerAddress(
        UUID id, AddressType type, String recipientName, String phone, String line1, String line2,
        String wardCode, String wardName, String provinceCode, String provinceName,
        String countryCode, String postalCode, boolean defaultAddress, long version
) {
    public CustomerAddress {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        recipientName = required(recipientName, "recipientName", 200);
        phone = VietnamPhoneValidator.normalise(required(phone, "phone", 32));
        line1 = required(line1, "line1", 255);
        line2 = optional(line2, 255);
        wardCode = required(wardCode, "wardCode", 20);
        wardName = required(wardName, "wardName", 120);
        provinceCode = required(provinceCode, "provinceCode", 20);
        provinceName = required(provinceName, "provinceName", 120);
        countryCode = required(countryCode, "countryCode", 2).toUpperCase(java.util.Locale.ROOT);
        postalCode = optional(postalCode, 20);
        if (!"VN".equals(countryCode)) throw new IllegalArgumentException("Only Vietnamese addresses are supported");
    }

    public CustomerAddress withDefault(boolean value) {
        return new CustomerAddress(id, type, recipientName, phone, line1, line2, wardCode,
                wardName, provinceCode, provinceName, countryCode, postalCode, value, version);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String trimmed = value.trim();
        if (trimmed.length() > max) throw new IllegalArgumentException(field + " is too long");
        return trimmed;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) throw new IllegalArgumentException("Address value is too long");
        return trimmed;
    }
}
