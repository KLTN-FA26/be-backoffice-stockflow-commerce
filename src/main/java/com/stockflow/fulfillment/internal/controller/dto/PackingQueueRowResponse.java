package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One order waiting at the packing table (SCRUM-281).
 *
 * @param pickerName    who picked it
 * @param packerId      who took it at the packing table; null while nobody has
 * @param packageCount  packages not voided; {@code sealedPackageCount} of them sealed
 * @param shipmentCount shipments completed so far (a split order has several)
 */
public record PackingQueueRowResponse(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId,
                                      String packStatus, Instant pickedAt, String pickerName, UUID packerId,
                                      String packerName, Instant packingStartedAt, int packageCount,
                                      int sealedPackageCount, int shipmentCount) {
}
