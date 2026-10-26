package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "StockPackageDetail", description = "One carton looked up by its LPN, with the stock rows in it (SCRUM-473)")
public record StockPackageDetailResponse(
        @Schema(example = "LPN-20261026-000001") String lpn,
        String sku,
        @Schema(description = "The packing unit the carton was made in", example = "CARTON") String unitOfMeasure,
        @Schema(description = "What it was made to hold: its unit's factor then") int nominalQty,
        @Schema(description = "What was counted into it at receipt; null until counted") Integer receivedQty,
        @Schema(description = "Of the stock it holds (one lot at most); null when it holds nothing") String lotNumber,
        LocalDate expiryDate,
        @Schema(description = "What is left in it: the sum of its stock rows; 0 before it is received") int onHand,
        @Schema(description = "Of onHand, the units held for orders") int reserved,
        @Schema(description = "RECEIVING, IN_STOCK, CONSUMED, LOST or VOID") String status,
        @Schema(description = "Still closed as counted: then it holds exactly receivedQty") boolean unopened,
        @Schema(example = "HCM-A01-1-A") String locationCode,
        @Schema(description = "The goods receipt line it arrived on; null for one made in the warehouse")
        UUID goodsReceiptLineId,
        @Schema(description = "When it was emptied, lost or voided") Instant closedAt,
        @Schema(description = "Its stock rows holding something, earliest expiry first") List<Content> contents
) {

    @Schema(name = "StockPackageContent", description = "One stock row inside a carton")
    public record Content(
            String lotNumber,
            LocalDate expiryDate,
            Instant receivedAt,
            @Schema(description = "INBOUND, AVAILABLE, QUARANTINE, BLOCKED, DAMAGED or EXPIRED") String status,
            int onHand,
            int reserved
    ) {
    }
}
