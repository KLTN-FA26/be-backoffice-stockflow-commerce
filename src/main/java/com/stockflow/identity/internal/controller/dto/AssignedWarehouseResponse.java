package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** A warehouse the caller is assigned to, as the warehouse workspace picker offers it. */
@Schema(description = "A warehouse the caller is assigned to")
public record AssignedWarehouseResponse(
        UUID id,
        @Schema(description = "The warehouse's prefix, which its location codes and the workspace URL start with",
                example = "BD")
        String prefix) {
}
