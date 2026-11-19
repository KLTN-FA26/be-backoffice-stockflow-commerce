package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A carton waiting in the put-back queue: picked for an order that was cancelled afterwards, to go back onto a bin
 * (kltn-docs 07 BR-05, §4.5 step 13), or removed at the check station as damaged, to go whole into a quarantine area
 * (08 §4.2 step 4, team plan 4C-6), or taken out of a shipment the warehouse manager cancelled before dispatch - into
 * quarantine when marked damaged, onto a bin otherwise (09 §5.1, RTM-RTDCANCEL).
 *
 * @param lpn              the carton: scan it to put it back
 * @param pickedQty        what goes back: the carton's count, whole
 * @param fromLocationCode where it was picked from: the natural bin to put it back on
 * @param quarantine       removed as damaged (at the check station, or marked so when its shipment was cancelled): it
 *                         goes into a QUARANTINE area of the warehouse, not onto a bin
 */
public record PutBackRowResponse(UUID taskId, String orderNumber, UUID warehouseId, UUID lineId, String sku,
                                 String lotNumber, String lpn, int pickedQty, String fromLocationCode,
                                 Instant pickedAt, boolean quarantine) {
}
