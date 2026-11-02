package com.stockflow.inventory.internal.controller.dto;

import java.util.List;
import java.util.UUID;

/**
 * A putaway task as the screen shows it.
 *
 * @param left                what is still to put away
 * @param sourceLocationCode  where the goods stand now
 * @param lpn                 the carton's; null for a loose task
 * @param targetLocationCode  the bin of the latest putaway; null until something was put away
 * @param suggestions         ranked bins; empty in a list, filled when one task is read
 */
public record PutawayTaskResponse(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, String lotNumber,
                                  int quantity, int left, String sourceLocationCode, String lpn, String status,
                                  String targetLocationCode, List<PutawaySuggestionResponse> suggestions) {
}
