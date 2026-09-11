package com.stockflow.inventory.internal.domain;

import java.util.UUID;

/**
 * Strongly typed identifier for the {@link StockItem} aggregate.
 *
 * <p>Passing bare {@code UUID}s around is how a codebase ends up calling
 * {@code repository.findById(orderId)} and getting an empty Optional it spends an afternoon
 * debugging. A wrapper type turns that into a compile error.</p>
 */
public record StockItemId(UUID value) {

    public StockItemId {
        if (value == null) {
            throw new IllegalArgumentException("StockItemId must not be null");
        }
    }

    public static StockItemId newId() {
        return new StockItemId(UUID.randomUUID());
    }

    public static StockItemId of(String raw) {
        return new StockItemId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
