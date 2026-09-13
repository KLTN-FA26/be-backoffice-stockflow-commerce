package com.stockflow.product.internal.domain;

import com.stockflow.common.id.Identifiers;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for the {@link Product} aggregate.
 *
 * <p>Passing bare {@code UUID}s around is how a codebase ends up calling
 * {@code repository.findById(categoryId)} and getting an empty Optional it spends an afternoon
 * debugging. A wrapper type turns that into a compile error.</p>
 */
public record ProductId(UUID value) {

    public ProductId {
        Objects.requireNonNull(value, "ProductId must not be null");
    }

    /** {@link Identifiers#newId()}, not {@code UUID.randomUUID()} — see CLAUDE.md §7. */
    public static ProductId newId() {
        return new ProductId(Identifiers.newId());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
