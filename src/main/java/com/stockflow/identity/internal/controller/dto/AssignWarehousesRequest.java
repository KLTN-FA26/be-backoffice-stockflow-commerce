package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Body of {@code PUT /api/v1/identity/users/{userId}/warehouses}. */
@Schema(description = "The complete set of warehouses the account works in; empty removes them all")
public record AssignWarehousesRequest(@NotNull @Size(max = 50) List<@NotNull UUID> warehouseIds) {
}
