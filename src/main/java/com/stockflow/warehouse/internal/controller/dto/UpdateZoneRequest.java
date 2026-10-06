package com.stockflow.warehouse.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** @param version the version the edit was based on, from the last read */
public record UpdateZoneRequest(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
        @PositiveOrZero long version
) {
}
