package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order a fulfillment task may be opened for (GET /fulfillment/task-candidates).
 *
 * @param lineCount     how many lines the order has
 * @param totalQuantity units over every line
 * @param lines         every line; a {@code printed} line is made by production and packed, never picked
 * @param orderStatus   READY_TO_FULFILL (or PARTIALLY_FULFILLED, a later batch); AWAITING_STOCK for an order whose part
 *                      held is picked before its release (kltn-docs 07 §4.1 steps 1-2): to the staging area, or to
 *                      dispatch only when {@code partialShipmentAllowed}
 * @param partialShipmentAllowed Sales allowed the order to ship in parts (17 BR-15)
 */
public record TaskCandidateResponse(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String customerName,
        UUID warehouseId,
        Instant placedAt,
        Instant releasedAt,
        int lineCount,
        int totalQuantity,
        List<Line> lines,
        String orderStatus,
        boolean partialShipmentAllowed
) {

    public record Line(UUID orderLineId, String sku, int quantity, boolean printed) {
    }
}
