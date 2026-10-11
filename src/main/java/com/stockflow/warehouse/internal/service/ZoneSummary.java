package com.stockflow.warehouse.internal.service;

import java.util.UUID;

public record ZoneSummary(UUID id, UUID warehouseId, String name, String color, long version) {
}
