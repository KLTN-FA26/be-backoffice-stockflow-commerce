package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cycle count in the list: its header and how far counting got. The lines are on {@code GET /{countId}} only, so a
 * page of counts stays the size of the page, not of every line on it.
 */
@Schema(name = "CycleCountSummary")
public record CycleCountSummaryResponse(UUID id, String countNumber, UUID warehouseId, String triggerType,
                                        String status, boolean blind, UUID assignedUserId, LocalDate plannedDate,
                                        Instant startedAt, Instant completedAt,
                                        @Schema(description = "Absent on a blind count for a counter") String note,
                                        Instant createdAt,
                                        @Schema(description = "Lines on the count") int lineCount,
                                        @Schema(description = "Lines counted so far") int countedCount,
                                        @Schema(description = "Counted lines that differ from the books; absent for a "
                                                + "caller who may not decide counts (inventory-cycle-counts:APPROVE)")
                                        Integer varianceCount) {
}
