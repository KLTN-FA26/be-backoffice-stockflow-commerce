package com.stockflow.fulfillment.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One shipment as the shipment list shows it ({@code GET /api/v1/fulfillment/shipments}). Every order ships as a full
 * truckload since the rescope: no delivery mode and nothing collected on delivery (V20261108000400).
 *
 * @param code          {@code <order number>-S<n>}
 * @param status        READY_TO_DISPATCH, HANDED_OVER, DELIVERED, DELIVERY_FAILED, RETURNED or CANCELLED
 * @param carrierName   the carrier chosen; null while none is
 * @param loadCode      the load it is on; null when on none
 * @param partial       part of an order shipped while the rest waits
 * @param outcomeAt     when the carrier's outcome was recorded: delivered, not delivered or back at the warehouse
 * @param failureReason why the last failed attempt failed: CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED or
 *                      OTHER. It stays once the shipment is delivered or back after a retry; null if none failed
 */
public record ShipmentSummary(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID warehouseId,
                              String status, int packageCount, String carrierName, String loadCode, boolean partial,
                              Instant readyAt,
                              Instant handedOverAt, Instant outcomeAt, String failureReason) {
}
