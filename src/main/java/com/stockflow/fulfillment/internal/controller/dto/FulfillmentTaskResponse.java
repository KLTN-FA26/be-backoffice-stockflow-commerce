package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A pick/pack task with the design-integrity evidence gathered for it. No storage keys.
 *
 * <p>{@code lines} is present only when one task is read by id ({@code GET /tasks/{taskId}}); list rows
 * and the answers of the write endpoints leave it out.</p>
 */
public record FulfillmentTaskResponse(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId,
                                      UUID assignedUserId, String assignedUserName, String pickStatus,
                                      String packStatus, Instant assignedAt, Instant startedAt,
                                      Instant pickedAt, Instant packedAt, List<IntegrityEvidence> integrityEvidence,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) List<TaskLine> lines) {
    public record IntegrityEvidence(UUID orderLineId, UUID snapshotId, String expectedChecksum,
                                    String actualChecksum, boolean matching, Instant verifiedAt) { }

    /** One order line to pick. {@code printLine}: made to the customer's design, so it has a snapshot. */
    public record TaskLine(UUID lineId, String sku, int quantity, UUID designSnapshotId, boolean printLine) { }
}
