package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a pick list: where to go, what to take, how many, and what happened to it.
 *
 * @param barcode         the SKU's barcode; a scan may send it instead of the SKU. Null when it has none
 * @param status          {@code OPEN}, {@code PICKED}, {@code SHORT}, {@code CANCELLED} or {@code PUT_BACK}
 * @param putBackLocationCode the bin the goods went back onto; null unless {@code PUT_BACK}
 * @param shortQty        units the picker did not find at the bin (X-SHORT); 0 unless the line was short. A
 *                        {@code CANCELLED} line with a shortfall was reallocated: the lines after it pick it
 */
public record PickLineResponse(UUID lineId, UUID taskId, int sequenceNo, UUID orderLineId, String sku, String barcode,
                               String locationCode, String lotNumber, int allocatedQty, int pickedQty, String status,
                               Instant pickedAt, int putBackQty, String putBackLocationCode, Instant putBackAt,
                               int shortQty, String lpn, boolean wholeCarton) {
}
