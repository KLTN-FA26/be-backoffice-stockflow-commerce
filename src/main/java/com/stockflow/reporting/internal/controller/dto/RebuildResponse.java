package com.stockflow.reporting.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "What a rebuild recomputed")
public record RebuildResponse(
        LocalDate from,
        LocalDate to,
        @Schema(description = "Days recomputed", example = "30") int days,
        @Schema(description = "Orders found on those days, cancelled ones included, DRAFT not", example = "58")
        long ordersSeen,
        @Schema(description = "Of those, the ones that count: not cancelled", example = "54") long ordersCounted) {
}
