package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The planned count, and the slots left out because another count holds them. */
@Schema(name = "PlannedCycleCount")
public record PlannedCycleCountResponse(CycleCountResponse count, List<Skipped> skipped) {

    @Schema(name = "SkippedCountSlot", description = "A slot on another open count, or one whose variance still "
            + "waits for approval")
    public record Skipped(String locationCode, String sku, String lotNumber, String lpn, String countNumber) {
    }
}
