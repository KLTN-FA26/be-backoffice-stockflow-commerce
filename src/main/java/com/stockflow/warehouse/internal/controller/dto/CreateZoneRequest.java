package com.stockflow.warehouse.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** @param color {@code #RRGGBB}, either case; optional */
public record CreateZoneRequest(
        @Schema(example = "Zone A - Sofas")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "#RRGGBB, optional", example = "#4F81BD")
        @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color
) {
}
