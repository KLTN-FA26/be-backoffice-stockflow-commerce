package com.stockflow.inventory.internal.repository;

/** One row of the grouped ATP query: {@code sum()} over an int column comes back as a Long. */
public record SkuAtpRow(String sku, Long atp) {
}
