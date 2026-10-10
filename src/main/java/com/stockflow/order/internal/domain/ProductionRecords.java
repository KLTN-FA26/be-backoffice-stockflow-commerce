package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the order module knows about production, learned from {@code SampleApproved} and
 * {@code ProductionCompleted} and kept in its own schema ({@code ordering.approved_sample},
 * {@code ordering.order_line_production}). Release and the move to READY_TO_FULFILL read it; the
 * production module is never queried.
 */
public interface ProductionRecords {

    /** The approved sample of this design on this blank, the latest if there were several. */
    Optional<UUID> approvedSample(UUID designSnapshotId, String blankSku);

    /**
     * Whether this design was already printed to completion for another order: a repeat design needs
     * no new sample (BR-PRD-08).
     */
    boolean producedBefore(UUID designSnapshotId, UUID excludingOrderId);

    /** Idempotent: a sample approved twice is recorded once. */
    void recordSampleApproved(UUID sampleRequestId, UUID customerId, UUID designSnapshotId, String blankSku,
                              Instant approvedAt);

    /** Idempotent: false when this production order was already recorded. */
    boolean recordCompleted(UUID productionOrderId, UUID orderLineId, int goodQuantity, Instant completedAt);

    /** Good units finished so far, per line of the order. */
    Map<UUID, Integer> producedByLine(UUID orderId);
}
