package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(name = "StockAlertSummary", description = "Open (not resolved) alerts per kind, inside the caller's warehouses")
public record StockAlertSummaryResponse(
        @Schema(description = "Every kind is present, zero when there is none")
        Map<String, Long> openByKind) {
}
