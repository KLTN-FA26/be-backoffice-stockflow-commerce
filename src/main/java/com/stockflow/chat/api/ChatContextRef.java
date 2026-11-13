package com.stockflow.chat.api;

import java.util.Objects;
import java.util.UUID;

/** A document a conversation is about: its kind and its id (for a customer's SKU, the variant's id). */
public record ChatContextRef(ChatContextKind kind, UUID id) {

    public ChatContextRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }
}
