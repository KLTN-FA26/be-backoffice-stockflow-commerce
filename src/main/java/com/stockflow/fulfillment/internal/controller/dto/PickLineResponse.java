package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a pick list - one carton: where to go, which carton to take, and what happened to it (kltn-docs 07 §5.2).
 *
 * @param barcode         the SKU's barcode, for the picker to read; scanning it confirms nothing (07 BR-10). Null when
 *                        the SKU has none
 * @param allocatedQty    the carton's standard count: the line takes it whole, no quantity is entered (BR-04)
 * @param status          {@code OPEN}, {@code STAGED}, {@code PICKED}, {@code SHORT}, {@code CANCELLED} or
 *                        {@code PUT_BACK}. {@code STAGED}: the carton is in the task's staging area, held for the order,
 *                        not deducted (team plan 4C-5)
 * @param putBackLocationCode the bin the carton went back onto; null unless {@code PUT_BACK}
 * @param shortQty        units the picker could not take (X-SHORT): the whole carton for a short reported since 4C-4,
 *                        0 unless the line was short. A {@code CANCELLED} line with a shortfall was reallocated: the
 *                        lines after it pick it
 * @param shortReason     {@code NOT_FOUND} or {@code DAMAGED}: why the carton could not be taken; null otherwise
 * @param lpn             the carton: scan it to confirm the line, and again to put it back. Null only on lines of
 *                        loose stock from before cartons
 * @param stagedAt        when the carton was moved to the staging area; null for a line never staged
 * @param stagedLocationCode the staging area it was moved to; null for a line never staged
 * @param removalReason   why the check station removed the line's carton ({@code DAMAGED}, {@code ORDER_CANCELLED},
 *                        ...); null while it was not removed. The line stays {@code PICKED}; a damaged carton goes to
 *                        quarantine (08 §4.2 step 4)
 * @param replacementPending the carton was removed as damaged and its order line still lacks it, so the warehouse
 *                        manager may replace it through the reallocation (07 §4.4 step 10); false once replaced
 */
public record PickLineResponse(UUID lineId, UUID taskId, int sequenceNo, UUID orderLineId, String sku, String barcode,
                               String locationCode, String lotNumber, int allocatedQty, int pickedQty, String status,
                               Instant pickedAt, int putBackQty, String putBackLocationCode, Instant putBackAt,
                               int shortQty, String shortReason, String lpn, boolean wholeCarton, Instant stagedAt,
                               String stagedLocationCode, String removalReason, boolean replacementPending) {
}
