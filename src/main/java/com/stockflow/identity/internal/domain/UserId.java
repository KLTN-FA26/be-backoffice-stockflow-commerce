package com.stockflow.identity.internal.domain;

import com.stockflow.common.id.Identifiers;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed identifier for the {@link User} aggregate. */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "UserId must not be null");
    }

    /** {@link Identifiers#newId()}, not {@code UUID.randomUUID()} — see CLAUDE.md §7. */
    public static UserId newId() {
        return new UserId(Identifiers.newId());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
