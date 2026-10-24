package com.stockflow.inventory.internal.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cycle count as the list reads it: the header and how far counting got, without its lines - a page of counts must
 * not carry every line of every count, so the lines are read one count at a time ({@link CycleCounts#get}).
 *
 * @param countedCount  lines counted so far, of {@code lineCount}
 * @param varianceCount counted lines whose count differs from the books; null for a caller who may not decide counts,
 *                      since even the number of differing lines tells a counter something about the books
 * @param note          null on a blind count for a caller who may not decide counts, as in {@link CycleCountView}
 */
public record CycleCountSummary(UUID id, String number, UUID warehouseId, String triggerType, String status,
                                boolean blind, UUID assignedUserId, LocalDate plannedDate, Instant startedAt,
                                Instant completedAt, String note, Instant createdAt, int lineCount,
                                int countedCount, Integer varianceCount) {
}
