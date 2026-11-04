package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/v1/identity/roles/{roleCode}}. */
@Schema(description = "Rename a custom role")
public record UpdateRoleRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 1000) String description,
        @Schema(allowableValues = {"OWN", "WAREHOUSE", "ALL"}, description = "Omit to keep it") String dataScope,
        @Schema(description = "The version the editor loaded") @NotNull @PositiveOrZero Long version) {
}
