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
                                @Schema(description = "Share of the bins' volume the capacity board counts, (0, 1]: "
                                        + "0.85 is 85% (kltn-docs 06 §4.6)", example = "0.85")
                                BigDecimal utilizationFactor,
                                long version, Instant createdAt) {
}
