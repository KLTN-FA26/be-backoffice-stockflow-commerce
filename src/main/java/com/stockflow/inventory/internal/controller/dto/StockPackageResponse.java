package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "StockPackage", description = "One carton in a list of cartons (SCRUM-473)")
public record StockPackageResponse(
        @Schema(example = "LPN-20261026-000001") String lpn,
        String sku,
        @Schema(example = "CARTON") String unitOfMeasure,
        @Schema(description = "What it was made to hold: its unit's factor then") int nominalQty,
        @Schema(description = "What was counted into it at receipt; null until counted") Integer receivedQty,
        String lotNumber,
        @Schema(description = "What is left in it") int onHand,
        @Schema(description = "Of onHand, the units held for orders") int reserved,
        @Schema(description = "RECEIVING, IN_STOCK, CONSUMED, LOST or VOID") String status,
        String locationCode,
        UUID goodsReceiptLineId,
        @Schema(description = "A return receipt found it opened or empty (WF-05 Q-18): never sold again")
        boolean openedOnReturn,
        @Schema(description = "When a return receipt found it opened or empty; null when it never came back so")
        Instant returnOpenedAt,
        @Schema(description = "Whose goods it is after it last came back on a return request (WF-05 Q-4; audit r3 "
                + "RAD-4): PENDING (refund not decided: no release, no write-off), COMPANY, CUSTOMER_HELD, "
                + "CUSTOMER_SENT_BACK, CUSTOMER_DESTROYED; null when it never came back on one",
                allowableValues = {"PENDING", "COMPANY", "CUSTOMER_HELD", "CUSTOMER_SENT_BACK", "CUSTOMER_DESTROYED"})
        String returnOwnership,
        @Schema(description = "The return request it last came back on") UUID returnRequestId,
        @Schema(description = "That return's RMA number") String returnRmaNumber
) {
}
