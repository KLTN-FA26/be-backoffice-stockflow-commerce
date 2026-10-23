package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A cycle count with its lines. On a blind count, for a caller who may not decide counts, {@code note},
 * {@code expectedQty} and {@code adjustments} are left out: the variance would give the book quantity away.
 */
@Schema(name = "CycleCount")
public record CycleCountResponse(UUID id, String countNumber, UUID warehouseId, String triggerType, String status,
                                 boolean blind, UUID assignedUserId, LocalDate plannedDate, Instant startedAt,
                                 Instant completedAt,
                                 @Schema(description = "Absent on a blind count for a counter") String note,
                                 Instant createdAt, List<Line> lines) {

    @Schema(name = "CycleCountLine")
    public record Line(UUID id, String locationCode, String sku, String lotNumber,
                       @Schema(description = "The carton counted; absent for loose stock") UUID stockPackageId,
                       String lpn,
                       @Schema(description = "What the books say; absent on a blind count for a counter")
                       Integer expectedQty,
                       Integer countedQty, UUID countedBy, Instant countedAt, boolean recountRequired,
                       @Schema(description = "The variance adjustments raised from this line; empty on a blind count "
                               + "for a counter")
                       List<Adjustment> adjustments) {
    }

    @Schema(name = "CycleCountLineAdjustment")
    public record Adjustment(UUID id, String number, int quantityDelta, String status) {
    }
}
