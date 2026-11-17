package com.stockflow.order.internal.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 4B-8 (kltn-docs 17 §4.5, BR-29): the delivery batch of an order whose goods are on their way out - the latest one
 * released. Its shipments carry it, they leave from the first day of its delivery window in force (09 BR-04), and the
 * units of the batches after it are not "left behind" by them (17 BR-15).
 *
 * @param windowStart the first day of its window in force (the contract's, or the latest change, BR-28); null for a
 *                    batch whose window was never set
 * @param laterUnits  the units the order's later batches carry, not released yet
 */
public record BatchInFlight(UUID batchId, int seq, LocalDate windowStart, int laterUnits) {

    public BatchInFlight {
        java.util.Objects.requireNonNull(batchId, "batchId");
        if (laterUnits < 0) {
            throw new IllegalArgumentException("laterUnits is not negative");
        }
    }
}
