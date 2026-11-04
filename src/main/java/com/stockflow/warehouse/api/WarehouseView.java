package com.stockflow.warehouse.api;

import java.util.UUID;

/**
 * A warehouse as other modules see it: enough to name it on a document and to refuse it when it is
 * not in use. {@code prefix} is the short code every location and document number of it starts with.
 */
public record WarehouseView(UUID id, String prefix, String name, String address, boolean active) {
}
