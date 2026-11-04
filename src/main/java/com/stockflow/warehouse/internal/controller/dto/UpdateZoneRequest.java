package com.stockflow.warehouse.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** @param version the version the edit was based on, from the last read */
public record UpdateZoneRequest(
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "#RRGGBB, optional", example = "#4F81BD")
        @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
        @Schema(description = "The version from the last read; a stale one answers 409 OPTIMISTIC_LOCK")
        @PositiveOrZero long version
) {
}
