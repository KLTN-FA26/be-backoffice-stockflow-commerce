package com.stockflow.warehouse.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** @param color {@code #RRGGBB}, either case; optional */
public record CreateZoneRequest(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color
) {
}
