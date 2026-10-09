package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WarehouseResponse(UUID id, String prefix, String name, String address, String returnAddress,
                                MapUnit mapUnit, BigDecimal mapWidth, BigDecimal mapHeight,
                                WarehouseStatus status, long version, Instant createdAt) {
}
