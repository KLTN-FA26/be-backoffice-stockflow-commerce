package com.stockflow.reporting.internal.controller.dto;

import com.stockflow.common.api.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The stock summary of a period (2026-11-14 plan, BE2-C; kltn-docs 20 §4.7): per SKU and warehouse, opening, what came
 * in and went out by kind, and closing, each in pieces and cartons. Quantities only: the system values no stock.
 */
@Schema(name = "StockSummary", description = "Stock in, out and on hand per SKU and warehouse over a period")
public record StockSummaryResponse(
        @Schema(description = "First day of the period, inclusive (Asia/Ho_Chi_Minh)") LocalDate from,
        @Schema(description = "Last day of the period, inclusive") LocalDate to,
        PageResponse<Row> rows) {

    @Schema(name = "StockSummaryRow", description = "One SKU in one warehouse. closing = opening + received + "
            + "returned + putBack - picked + adjustedIn - adjustedOut + otherIn - otherOut")
    public record Row(
            @Schema(description = "Null for a warehouse no longer active") UUID warehouseId,
            @Schema(example = "HCM") String warehousePrefix,
            String warehouseName,
            String sku,
            @Schema(description = "The customer whose own SKU it is; null for an ordinary variant") UUID ownerId,
            String ownerCode,
            String ownerName,
            @Schema(description = "The SKU's standard count per carton; null when it has no carton unit")
            Integer unitsPerCarton,
            @Schema(description = "What the warehouse held before the period") Quantity opening,
            @Schema(description = "Goods received against production orders") Quantity received,
            @Schema(description = "Cartons a return receipt brought back (into quarantine)") Quantity returned,
            @Schema(description = "Picked cartons put back into stock") Quantity putBack,
            @Schema(description = "Goods picked out for orders") Quantity picked,
            @Schema(description = "Approved adjustments up") Quantity adjustedIn,
            @Schema(description = "Approved adjustments down (write-offs, lost cartons)") Quantity adjustedOut,
            @Schema(description = "Any other movement in: historical flows") Quantity otherIn,
            @Schema(description = "Any other movement out: historical flows") Quantity otherOut,
            @Schema(description = "What the warehouse held at the end of the period") Quantity closing,
            @Schema(description = "NONE, or why cartons are null: NO_CARTON_UNIT, NOT_WHOLE_CARTONS",
                    allowableValues = {"NONE", "NO_CARTON_UNIT", "NOT_WHOLE_CARTONS"}) String cartonNote) {
    }

    @Schema(name = "StockQuantity", description = "Pieces, and the same in cartons when it is a whole number of them")
    public record Quantity(long units, Long cartons) {
    }
}
