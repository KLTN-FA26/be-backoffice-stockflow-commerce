package com.stockflow.order.internal.controller;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderStatus;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code status} filter of the order lists. One sprint of grace for the names G3-2 retired, so a screen not yet
 * updated keeps working: {@code IN_FULFILMENT} reads as what it became ({@code PICKING}, {@code PARTIALLY_FULFILLED},
 * {@code PACKED}) and {@code PAID} as {@code CONFIRMED} (SCRUM-460). Anything else unknown is 400
 * {@code UNSUPPORTED_PARAMETER}, as before.
 */
final class OrderStatusFilter {

    private OrderStatusFilter() {
    }

    static List<OrderStatus> parse(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<OrderStatus> statuses = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String value : raw.split(",")) {
                String name = value.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                switch (name) {
                    case "IN_FULFILMENT" -> statuses.addAll(List.of(OrderStatus.PICKING,
                            OrderStatus.PARTIALLY_FULFILLED, OrderStatus.PACKED));
                    case "PAID" -> statuses.add(OrderStatus.CONFIRMED);
                    default -> statuses.add(known(name));
                }
            }
        }
        return List.copyOf(statuses);
    }

    private static OrderStatus known(String name) {
        try {
            return OrderStatus.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown order status",
                    List.of(FieldError.of("status", "must be one of " + Arrays.stream(OrderStatus.values())
                            .map(Enum::name).collect(Collectors.joining(", ")))));
        }
    }
}
