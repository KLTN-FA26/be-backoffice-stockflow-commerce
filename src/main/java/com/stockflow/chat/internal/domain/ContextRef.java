package com.stockflow.chat.internal.domain;

import java.util.Objects;
import java.util.UUID;

/** A document a conversation can be about: its kind and its id (for a customer's SKU, the variant's id). */
public record ContextRef(ContextKind kind, UUID id) {

    public ContextRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }
}
