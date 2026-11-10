package com.stockflow.inventory.internal.repository;

import java.util.UUID;

/** One stock row a release could allocate whole: the contents of a sealed, unheld carton in stock. */
public record AllocatableRow(UUID id, String sku, String locationCode, Integer units) {
}
