package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * A putaway task as the screen shows it.
 *
 * @param left                what is still to put away
 * @param sourceLocationCode  where the goods stand now
 * @param warehouseId         the task's warehouse: that of where its goods stand, since stock never changes warehouse
 * @param lpn                 the carton's; null for a loose task
 * @param targetLocationCode  the bin of the latest putaway; null until something was put away
 * @param suggestions         ranked bins; empty in a list, filled when one task is read
 * @param overflowLocationCodes when one task is read and no bin takes its carton: the usable {@code OVERFLOW} areas
 *                            of its warehouse, where it may be put instead (kltn-docs 05 BR-13); empty otherwise
 * @param source              {@code RECEIPT} or {@code QUARANTINE_RELEASE} (kltn-docs 05 BR-01); a release has no
 *                            receipt or receipt line
 */
public record PutawayTaskResponse(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, String lotNumber,
                                  int quantity, int left, String sourceLocationCode,
                                  @Schema(description = "The task's warehouse, that of where its goods stand")
                                  UUID warehouseId, String lpn, String status, String targetLocationCode,
                                  List<PutawaySuggestionResponse> suggestions,
                                  List<String> overflowLocationCodes, String source,
                                  @Schema(description = "The warehouse's code (its location prefix)", example = "HCM",
                                          nullable = true)
                                  String warehouseCode,
                                  @Schema(nullable = true) String warehouseName) {
}
