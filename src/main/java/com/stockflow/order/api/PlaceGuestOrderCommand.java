package com.stockflow.order.api;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.util.List;
import java.util.UUID;

public record PlaceGuestOrderCommand(
        UUID requestId,
        String email,
        Address shippingAddress,
        Address billingAddress,
        List<Line> lines
) {
    public PlaceGuestOrderCommand {
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email is required");
        if (shippingAddress == null) throw new IllegalArgumentException("shippingAddress is required");
        billingAddress = billingAddress == null ? shippingAddress : billingAddress;
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("An order needs at least one line");
        lines = List.copyOf(lines);
    }

    public record Address(String recipientName, String phone, String line1, String line2,
                          String wardCode, String wardName, String provinceCode,
                          String provinceName, String countryCode, String postalCode) {
    }

    public record Line(Sku sku, int quantity, Money unitPrice) {
        public Line {
            if (sku == null) throw new IllegalArgumentException("sku is required");
            if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
            if (unitPrice == null || unitPrice.isNegative()) {
                throw new IllegalArgumentException("unitPrice must not be negative");
            }
        }
    }
}
