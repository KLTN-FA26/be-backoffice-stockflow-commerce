package com.stockflow.procurement.internal.domain;

import com.stockflow.common.id.Identifiers;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed identifier for the {@link PurchaseOrder} aggregate. */
public record PurchaseOrderId(UUID value) {

    public PurchaseOrderId {
        Objects.requireNonNull(value, "PurchaseOrderId must not be null");
    }

    /** {@link Identifiers#newId()}, not {@code UUID.randomUUID()} — see CLAUDE.md §7. */
    public static PurchaseOrderId newId() {
        return new PurchaseOrderId(Identifiers.newId());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
