package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A pick/pack task with the design-integrity evidence gathered for it. No storage keys. */
public record FulfillmentTaskResponse(UUID taskId, UUID orderId, UUID assignedUserId, String pickStatus,
                                      String packStatus, Instant assignedAt, Instant startedAt,
                                      Instant pickedAt, Instant packedAt, List<IntegrityEvidence> integrityEvidence) {
    public record IntegrityEvidence(UUID orderLineId, UUID snapshotId, String expectedChecksum,
                                    String actualChecksum, boolean matching, Instant verifiedAt) { }
}
