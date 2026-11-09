package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A carton picked for an order that was cancelled afterwards, waiting to go back onto a bin (kltn-docs 07 BR-05, §4.5
 * step 13).
 *
 * @param lpn              the carton: scan it to put it back
 * @param pickedQty        what goes back: the carton's count, less any units found damaged at packing (then it is not
 *                         whole any more, and the put-back is refused)
 * @param fromLocationCode where it was picked from: the natural bin to put it back on
 */
public record PutBackRowResponse(UUID taskId, String orderNumber, UUID warehouseId, UUID lineId, String sku,
                                 String lotNumber, String lpn, int pickedQty, String fromLocationCode,
                                 Instant pickedAt) {
}
