package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.domain.CycleCountRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A cycle count as the web layer reads it: the aggregate flattened, with the LPN of each carton line and the
 * adjustments raised from each line. A value, not the aggregate, so a controller cannot call behaviour on it.
 * For a counter who may not decide, a blind count carries no booked state, no note and no adjustments (BR-07).
 */
public record CycleCountView(UUID id, String number, UUID warehouseId, String triggerType, String status,
                             boolean blind, UUID assignedUserId, LocalDate plannedDate, Instant startedAt,
                             Instant completedAt, String note, Instant createdAt, List<Line> lines) {

    public CycleCountView {
        lines = List.copyOf(lines);
    }

    /**
     * {@code lpn} is null only on a loose line of the history; {@code booked} null where a blind counter must not see
     * it; {@code found} null until the line is counted.
     */
    public record Line(UUID id, String locationCode, String sku, String lotNumber, UUID stockPackageId,
                       String lpn, Boolean booked, Boolean found, UUID countedBy, Instant countedAt,
                       boolean recountRequired, List<CycleCountRepository.LineAdjustment> adjustments) {

        public Line {
            adjustments = List.copyOf(adjustments);
        }
    }
}
