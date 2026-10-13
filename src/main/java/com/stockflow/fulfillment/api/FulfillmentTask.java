package com.stockflow.fulfillment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task-scoped fulfillment view; it deliberately exposes no design storage key.
 *
 * @param orderNumber      the number the order is known by, so a pick list needs no call to the orders API
 * @param warehouseId      the warehouse the goods are picked in (BR-SEC-002)
 * @param assignedUserName display name of the assignee; null while nobody is assigned
 * @param lines            what to pick, set only when one task is read by id; null on list rows
 */
public record FulfillmentTask(
        UUID taskId,
        UUID orderId,
        String orderNumber,
        UUID warehouseId,
        UUID assignedUserId,
        String assignedUserName,
        String pickStatus,
        String packStatus,
        Instant assignedAt,
        Instant startedAt,
        Instant pickedAt,
        Instant packedAt,
        List<IntegrityEvidence> integrityEvidence,
        List<Line> lines
) {
    public FulfillmentTask {
        integrityEvidence = integrityEvidence == null ? List.of() : List.copyOf(integrityEvidence);
        lines = lines == null ? null : List.copyOf(lines);
    }

    public record IntegrityEvidence(UUID orderLineId, UUID snapshotId, String expectedChecksum,
                                    String actualChecksum, boolean matching, Instant verifiedAt) { }

    /** One order line to pick (docs 07 Input: order number and lines with SKU and quantity). */
    public record Line(UUID lineId, String sku, int quantity, UUID designSnapshotId, boolean printLine) { }
}
