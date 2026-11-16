package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "Warehouse")
public record WarehouseResponse(UUID id, String prefix, String name, String address, String returnAddress,
                                MapUnit mapUnit, BigDecimal mapWidth, BigDecimal mapHeight,
                                WarehouseStatus status,
                                @Schema(description = "Share of the bins' computed volume cartons really fill, in "
                                        + "(0, 1]; scales the capacity board only (kltn-docs 06 §4.6)")
                                BigDecimal utilizationFactor,
                                long version, Instant createdAt) {
}
