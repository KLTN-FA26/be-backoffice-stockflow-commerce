package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a pick list - one carton: where to go, which carton to take, and what happened to it (kltn-docs 07 §5.2).
 *
 * @param barcode         the SKU's barcode, for the picker to read; scanning it confirms nothing (07 BR-10). Null when
 *                        the SKU has none
 * @param allocatedQty    the carton's standard count: the line takes it whole, no quantity is entered (BR-04)
 * @param status          {@code OPEN}, {@code PICKED}, {@code SHORT}, {@code CANCELLED} or {@code PUT_BACK}
 * @param putBackLocationCode the bin the carton went back onto; null unless {@code PUT_BACK}
 * @param shortQty        units the picker could not take (X-SHORT): the whole carton for a short reported since 4C-4,
 *                        0 unless the line was short. A {@code CANCELLED} line with a shortfall was reallocated: the
 *                        lines after it pick it. A SHORT line a damage report made is short by all of its units
 * @param shortReason     {@code NOT_FOUND} or {@code DAMAGED}: why the carton could not be taken; null otherwise
 * @param lpn             the carton: scan it to confirm the line, and again to put it back. Null only on lines of
 *                        loose stock from before cartons and on a SHORT line a damage report made
 * @param damagedQty      units the picker found damaged at the bin (SCRUM-277); positive only on the CANCELLED line a
 *                        damage report superseded: the lines with the same {@code damageReportId} pick what replaced it
 * @param damageReportId  the damage report that superseded or made the line; null otherwise
 * @param packingDamagedQty units of a PICKED line found damaged at the packing bench: back in quarantine, no longer
 *                        packed or put back; the lines with the report's {@code damageReportId} pick what replaced them
 */
public record PickLineResponse(UUID lineId, UUID taskId, int sequenceNo, UUID orderLineId, String sku, String barcode,
                               String locationCode, String lotNumber, int allocatedQty, int pickedQty, String status,
                               Instant pickedAt, int putBackQty, String putBackLocationCode, Instant putBackAt,
                               int shortQty, String shortReason, String lpn, boolean wholeCarton, int damagedQty,
                               UUID damageReportId, int packingDamagedQty) {
}
