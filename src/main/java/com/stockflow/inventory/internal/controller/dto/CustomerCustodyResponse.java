package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A carton held for a customer because their return refunded nothing (WF-05 Q-4), as the warehouse manager's custody
 * list shows it (TH-6): where it stands, whose return it came back on, what the customer chose, and how it ended.
 */
@Schema(name = "CustomerCustodyCarton", description = "A carton held for the customer (WF-05 Q-4): out of stock, never "
        + "sold or written off; handed back or destroyed only as the customer chose")
public record CustomerCustodyResponse(
        @Schema(example = "LPN-20261026-000001") String lpn,
        String sku,
        String lotNumber,
        @Schema(description = "The units it holds, its label") int quantity,
        @Schema(description = "Where it stands: the quarantine area it came back into", example = "HCM-QC01")
        String locationCode,
        @Schema(description = "The return request it came back on") UUID returnRequestId,
        @Schema(description = "CUSTOMER_HELD, CUSTOMER_SENT_BACK or CUSTOMER_DESTROYED") String ownership,
        @Schema(description = "Since when it is held for the customer: the return's refund decision") Instant heldAt,
        @Schema(description = "What the customer chose, as Sales recorded it on the return: SEND_BACK or "
                + "DESTROY_ON_BEHALF; null until they did - the warehouse then waits") String choice,
        @Schema(description = "When the warehouse manager handed it back or destroyed it") Instant endedAt,
        UUID endedBy,
        @Schema(description = "The record of the hand-over or the destruction") String note,
        @Schema(description = "Display name of endedBy; null until it ended", nullable = true) String endedByName
) {
}
