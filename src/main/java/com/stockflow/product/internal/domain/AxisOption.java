package com.stockflow.product.internal.domain;

import java.util.Objects;
import java.util.UUID;

/** One value a product offers on an axis. Its id is kept while the value stays. */
public record AxisOption(UUID id, UUID valueId) {

    public AxisOption {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(valueId, "valueId");
    }
}
