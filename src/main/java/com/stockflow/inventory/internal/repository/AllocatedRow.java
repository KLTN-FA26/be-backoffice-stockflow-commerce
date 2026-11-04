package com.stockflow.inventory.internal.repository;

/**
 * Units of one SKU allocated to one location (F-FUL step 02), built by a JPQL constructor expression.
 * {@code sum()} yields a {@code Long}, hence the widened quantity.
 */
public record AllocatedRow(String locationCode, long quantity) {
}
