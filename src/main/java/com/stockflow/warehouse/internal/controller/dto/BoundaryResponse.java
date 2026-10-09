package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.DoorStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** A wall or door. {@code operationalStatus} is {@code null} for a wall. */
@Schema(name = "Boundary", description = "A wall or a door; operationalStatus is absent for a wall")
public record BoundaryResponse(UUID id, UUID warehouseId, BoundaryType type, BigDecimal startX, BigDecimal startY,
                               BigDecimal endX, BigDecimal endY, boolean passable, DoorStatus operationalStatus,
                               long version) {
}
