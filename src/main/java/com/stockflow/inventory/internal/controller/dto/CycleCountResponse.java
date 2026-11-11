package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A cycle count with its lines, one per LPN carton (kltn-docs 20 §4.2). On a blind count, for a caller who may not
 * decide counts, {@code note}, {@code booked} and {@code adjustments} are left out: the variance would give the books
 * away (BR-07).
 */
@Schema(name = "CycleCount")
public record CycleCountResponse(UUID id, String countNumber, UUID warehouseId, String triggerType, String status,
                                 boolean blind, UUID assignedUserId, LocalDate plannedDate, Instant startedAt,
                                 Instant completedAt,
                                 @Schema(description = "Absent on a blind count for a counter") String note,
                                 Instant createdAt, List<Line> lines) {

    @Schema(name = "CycleCountLine")
    public record Line(UUID id, String locationCode, String sku, String lotNumber,
                       @Schema(description = "The carton counted; absent only on a loose line counted before "
                               + "counts were by carton") UUID stockPackageId,
                       String lpn,
                       @Schema(description = "Whether the books have the carton at this bin (read when it was "
                               + "counted); absent on a blind count for a counter")
                       Boolean booked,
                       @Schema(description = "Whether the carton was scanned here; absent until the line is counted")
                       Boolean found,
                       UUID countedBy, Instant countedAt, boolean recountRequired,
                       @Schema(description = "The variance adjustments raised from this line; empty on a blind count "
                               + "for a counter")
                       List<Adjustment> adjustments) {
    }

    @Schema(name = "CycleCountLineAdjustment")
    public record Adjustment(UUID id, String number, int quantityDelta, String status) {
    }
}
