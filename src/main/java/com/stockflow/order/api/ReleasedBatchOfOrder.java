package com.stockflow.order.api;

import java.util.UUID;

/**
 * The delivery batch an order was last released for (kltn-docs 17 BR-29, 07 BR-07): what its next pick task is opened
 * for, unless it has one already.
 */
public record ReleasedBatchOfOrder(UUID orderId, UUID batchId) {
}
