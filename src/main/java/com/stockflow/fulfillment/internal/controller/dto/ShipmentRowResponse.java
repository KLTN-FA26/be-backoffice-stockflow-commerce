package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A shipment in the back office (G4-2a, kltn-docs 09 §5).
 *
 * @param taskId         the fulfillment task it was packed in: reprint its label at
 *                       {@code GET /fulfillment/tasks/{taskId}/packages/{packageId}/label} (after LABEL_OUTDATED)
 * @param carrierId      the carrier it was handed to (its vehicle's booking); absent before the handover
 * @param loadId         the open or dispatched load it is on; null when on none
 * @param wholeOrderHandover handed over before loads existed (no manifest names it)
 */
public record ShipmentRowResponse(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID taskId,
                                  UUID warehouseId, String status, int packageCount, UUID carrierId,
                                  String carrierName, UUID loadId, String loadCode, boolean partial,
                                  Instant readyAt, Instant handedOverAt, Instant outcomeAt, String failureReason,
                                  boolean wholeOrderHandover, long version) {
}
