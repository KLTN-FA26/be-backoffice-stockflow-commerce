package com.stockflow.order.api;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * 4B-8 (kltn-docs 07 BR-07, 17 §4.5): a delivery batch released to the warehouse - one pick task picks it.
 *
 * @param holdIds     the allocations it stands for
 * @param windowStart the first day of its delivery window in force; null when it has none
 */
public record ReleasedBatch(UUID batchId, int seq, LocalDate windowStart, Set<UUID> holdIds) {

    public ReleasedBatch {
        holdIds = holdIds == null ? Set.of() : Set.copyOf(holdIds);
    }
}
