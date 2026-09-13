package com.stockflow.product.internal.domain;

import java.util.Objects;
import java.util.UUID;

/** One entry in a product's media gallery (WBS 3.1.1.2). */
public record ProductImage(UUID id, String url, int sortOrder) {

    public ProductImage {
        Objects.requireNonNull(id, "id");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Image url must not be blank");
        }
    }
}
