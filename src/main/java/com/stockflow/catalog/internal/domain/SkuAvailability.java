package com.stockflow.catalog.internal.domain;

import com.stockflow.common.domain.Sku;

import java.util.Objects;

/** The indicator for one SKU, as shown on a product page. */
public record SkuAvailability(Sku sku, Availability availability) {

    public SkuAvailability {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(availability, "availability");
    }
}
