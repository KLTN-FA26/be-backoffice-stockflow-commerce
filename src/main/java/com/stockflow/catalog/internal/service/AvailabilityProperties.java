package com.stockflow.catalog.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Storefront availability settings ({@code stockflow.catalog.availability}).
 *
 * @param lowStockThreshold at or under this many cartons a customer's SKU shows "low stock" (kltn-docs 13 §4.4 step 4:
 *                          5 cartons by default, 0 meaning never; open-questions D28 counts it in cartons, as the
 *                          stock it is compared with)
 * @param maxSkusPerRequest how many SKUs one call may ask about — a product page has a handful of
 *                          variants, a listing page one per card; anything far above that is a
 *                          scrape of the whole stock position through a public endpoint
 */
@ConfigurationProperties(prefix = "stockflow.catalog.availability")
public record AvailabilityProperties(
        @DefaultValue("5") int lowStockThreshold,
        @DefaultValue("50") int maxSkusPerRequest) {

    public AvailabilityProperties {
        if (lowStockThreshold < 0) {
            throw new IllegalArgumentException(
                    "stockflow.catalog.availability.low-stock-threshold must not be negative");
        }
        if (maxSkusPerRequest < 1) {
            throw new IllegalArgumentException(
                    "stockflow.catalog.availability.max-skus-per-request must be at least 1");
        }
    }
}
