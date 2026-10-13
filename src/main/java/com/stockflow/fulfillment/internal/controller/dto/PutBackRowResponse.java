package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Goods picked for an order that was cancelled afterwards, waiting to go back onto a bin (docs 07 BR-05).
 *
 * @param fromLocationCode where they were picked from: the natural bin to put them back on
 */
public record PutBackRowResponse(UUID taskId, String orderNumber, UUID warehouseId, UUID lineId, String sku,
                                 String lotNumber, int pickedQty, String fromLocationCode, Instant pickedAt) {
}
