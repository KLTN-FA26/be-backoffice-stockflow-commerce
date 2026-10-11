package com.stockflow.warehouse.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "Zone")
public record ZoneResponse(UUID id, UUID warehouseId, String name, String color, long version) {
}
