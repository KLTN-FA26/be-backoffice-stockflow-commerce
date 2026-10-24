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
        List<Line> lines
) {

    public record Line(UUID orderLineId, String sku, int quantity, boolean printed) {
    }
}
