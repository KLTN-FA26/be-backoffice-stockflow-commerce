package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record StockAdjustmentResponse(UUID adjustmentId, String number, String locationCode, String sku,
                                      String lotNumber,
                                      @io.swagger.v3.oas.annotations.media.Schema(description = "WRITE_OFF takes the "
                                              + "whole carton out of stock, RESTOCK puts a LOST carton back whole. "
                                              + "There is no quantity: a carton is adjusted whole",
                                              allowableValues = {"WRITE_OFF", "RESTOCK"})
                                      String direction, String reason, String note,
                                      String status, UUID requestedBy, Instant requestedAt, UUID decidedBy,
                                      Instant decidedAt, String rejectionReason, Instant postedAt,
                                      Instant withdrawnAt, long version, UUID stockPackageId,
                                      UUID cycleCountLineId,
                                      @io.swagger.v3.oas.annotations.media.Schema(description = "For a cycle count's "
                                              + "variance: who counted the line. Neither they nor the requester may "
                                              + "decide it", nullable = true)
                                      UUID countedBy,
                                      @io.swagger.v3.oas.annotations.media.Schema(description = "Display name of "
                                              + "requestedBy, so the screen need not read the user list; null when "
                                              + "the user is unknown", nullable = true)
                                      String requestedByName,
                                      @io.swagger.v3.oas.annotations.media.Schema(description = "Display name of "
                                              + "decidedBy; null while undecided", nullable = true)
                                      String decidedByName,
                                      @io.swagger.v3.oas.annotations.media.Schema(description = "Display name of "
                                              + "countedBy; null whenever countedBy is", nullable = true)
                                      String countedByName) {
}
