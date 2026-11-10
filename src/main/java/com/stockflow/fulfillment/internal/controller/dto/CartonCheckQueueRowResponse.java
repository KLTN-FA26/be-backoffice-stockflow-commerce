package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A picked order waiting at the check station (kltn-docs 08 §4.1 step 1).
 *
 * @param status       the check session: {@code PENDING} or {@code IN_PROGRESS} (or the status asked for)
 * @param startedBy    who started the check; null while PENDING
 * @param cartonCount  the session's cartons, not removed (none before it starts)
 * @param checkedCount of them, scanned
 * @param labeledCount of them, labelled
 */
public record CartonCheckQueueRowResponse(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId,
                                          String status, Instant pickedAt, String pickerName, UUID startedBy,
                                          String startedByName, Instant startedAt, int cartonCount, int checkedCount,
                                          int labeledCount, int shipmentCount) {
}
