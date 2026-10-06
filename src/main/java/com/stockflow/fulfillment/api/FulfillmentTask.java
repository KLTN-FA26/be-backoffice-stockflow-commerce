package com.stockflow.fulfillment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task-scoped fulfillment view; it deliberately exposes no design storage key. */
public record FulfillmentTask(
        UUID taskId,
        UUID orderId,
        UUID assignedUserId,
        String pickStatus,
        String packStatus,
        Instant assignedAt,
        Instant startedAt,
        Instant pickedAt,
        Instant packedAt,
        List<IntegrityEvidence> integrityEvidence
) {
    public FulfillmentTask {
        integrityEvidence = integrityEvidence == null ? List.of() : List.copyOf(integrityEvidence);
    }

    public record IntegrityEvidence(UUID orderLineId, UUID snapshotId, String expectedChecksum,
                                    String actualChecksum, boolean matching, Instant verifiedAt) { }
}
