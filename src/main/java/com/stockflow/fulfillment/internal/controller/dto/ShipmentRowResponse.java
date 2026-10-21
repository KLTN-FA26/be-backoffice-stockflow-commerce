package com.stockflow.fulfillment.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A shipment in the back office (G4-2a, kltn-docs 09 §5).
 *
 * @param taskId         the fulfillment task it was packed in: reprint its label at
 *                       {@code GET /fulfillment/tasks/{taskId}/packages/{packageId}/label} (after LABEL_OUTDATED)
 * @param codAmount      what the carrier collects: the label's figure until the handover fixes it (09 BR-04)
 * @param currency       of {@code codAmount}
 * @param loadId         the open or dispatched load it is on; null when on none
 * @param wholeOrderHandover handed over before loads existed (no manifest names it)
 */
public record ShipmentRowResponse(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID taskId,
                                  UUID warehouseId, String status, String deliveryMode, int packageCount,
                                  BigDecimal codAmount, String currency, UUID carrierId, String carrierName, UUID loadId, String loadCode, boolean partial,
                                  Instant readyAt, Instant handedOverAt, Instant outcomeAt, String failureReason,
                                  boolean wholeOrderHandover, long version) {
}
