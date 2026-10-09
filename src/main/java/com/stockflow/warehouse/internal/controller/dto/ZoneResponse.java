package com.stockflow.warehouse.internal.controller.dto;

import java.util.UUID;

public record ZoneResponse(UUID id, UUID warehouseId, String name, String color, long version) {
}
