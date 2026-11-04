package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One order waiting at the packing table (SCRUM-281).
 *
 * @param status        the row's packing status - {@code PENDING} (nobody took it yet), {@code PACKING} (at a table),
 *                      {@code ON_HOLD} (a design check): the field the {@code status} filter of
 *                      {@code GET /packing-queue} matches (E2E QA 2026-10-21: rows had none)
 * @param packStatus    the same value, under the name the task and the packing session use
 * @param pickerName    who picked it
 * @param packerId      who took it at the packing table; null while nobody has
 * @param packageCount  packages not voided; {@code sealedPackageCount} of them sealed
 * @param shipmentCount shipments completed so far (a split order has several)
 */
public record PackingQueueRowResponse(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId,
                                      String status, String packStatus, Instant pickedAt, String pickerName, UUID packerId,
                                      String packerName, Instant packingStartedAt, int packageCount,
                                      int sealedPackageCount, int shipmentCount) {
}
