package com.stockflow.contracts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the Order Coordinator releases an order whose lines
 * must be printed before they can be picked. The <b>production</b> module turns each line into an
 * {@code ORDER} production order (plan 2026-10-06 §3.3, BR-PRD-08/09).
 *
 * <p>One event per release, carrying every line that needs printing, so the production orders of
 * one customer order are created together or not at all. Lines that ship from stock as they are
 * do not appear here.</p>
 *
 * <p>The design is carried as snapshot id <i>and</i> checksum: production prints from the locked
 * snapshot and refuses a file whose checksum differs (production BR-02). Carrying the checksum
 * the order was accepted against means a snapshot changed after acceptance is caught at prepress
 * instead of printed.</p>
 *
 * <p>Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made
 * durable by Spring Modulith's event publication registry. See {@code package-info.java} for the
 * rules on changing this contract.</p>
 */
public record OrderLinesReleasedForProduction(
        UUID orderId,
        String orderNumber,
        UUID warehouseId,
        List<Line> lines
) {

    /**
     * One line to print.
     *
     * @param orderLineId      the order line the production order fulfils; {@link ProductionCompleted}
     *                         names it back
     * @param blankSku         the unprinted cup or box issued to production
     * @param quantity         good units the customer ordered; production plans its own overage
     * @param designSnapshotId the locked design to print
     * @param designChecksum   checksum of that snapshot when the order was accepted
     * @param approvedSampleId the sample the customer approved for a new design; null for a repeat
     *                         design that needs no sample (BR-PRD-08)
     * @param dueDate          the date the line must be ready to fulfil; null when none was promised
     */
    public record Line(
            UUID orderLineId,
            String blankSku,
            int quantity,
            UUID designSnapshotId,
            String designChecksum,
            UUID approvedSampleId,
            LocalDate dueDate
    ) {
    }
}
