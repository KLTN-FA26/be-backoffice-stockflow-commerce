package com.stockflow.inventory.internal.domain;

import java.util.UUID;

/** Strongly typed identifier for the {@link StockPackage} aggregate. */
public record StockPackageId(UUID value) {

    public StockPackageId {
        if (value == null) {
            throw new IllegalArgumentException("StockPackageId must not be null");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
