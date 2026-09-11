package com.stockflow.order.internal.domain;

import java.util.UUID;

/** Strongly typed identifier for the {@link Order} aggregate. */
public record OrderId(UUID value) {

    public OrderId {
        if (value == null) {
            throw new IllegalArgumentException("OrderId must not be null");
        }
    }

    public static OrderId newId() {
        return new OrderId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
