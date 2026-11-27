package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "StockPackage", description = "One carton in a list of cartons (SCRUM-473)")
public record StockPackageResponse(
        @Schema(example = "LPN-20261026-000001") String lpn,
        String sku,
        @Schema(example = "CARTON") String unitOfMeasure,
        @Schema(description = "What it was made to hold: its unit's factor then") int nominalQty,
        @Schema(description = "When it was counted in at receiving; null until counted. A counted carton stands for "
                + "nominalQty units", nullable = true) Instant countedAt,
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
        @Schema(description = "That return's RMA number") String returnRmaNumber,
        @Schema(description = "The warehouse its location is in") UUID warehouseId,
        @Schema(description = "That warehouse's code, its location prefix", example = "HCM") String warehouseCode,
        @Schema(description = "The product its SKU is a variant of; null when the catalogue does not know it")
        String productName,
        @Schema(description = "The variant its SKU is, as quotes and orders name it") String variantName,
        @Schema(description = "The distinct stock statuses of its stock rows holding something: a quarantined or "
                + "damaged carton is IN_STOCK as a carton but QUARANTINE / DAMAGED here; empty when it holds nothing",
                example = "[\"AVAILABLE\"]")
        List<String> stockStatuses,
        @Schema(description = "The oldest receipt of its stock, for FIFO; null when it holds nothing")
        Instant receivedAt,
        @Schema(description = "The goods receipt it arrived on; null with goodsReceiptLineId") UUID goodsReceiptId,
        @Schema(description = "That receipt's number") String goodsReceiptNumber,
        @Schema(description = "Where it came from: GOODS_RECEIPT (from a producer, on a goods receipt), RETURN (last "
                + "came back on a return receipt), IN_WAREHOUSE (boxed in the warehouse), PRODUCTION (in-house "
                + "production, history only)",
                allowableValues = {"GOODS_RECEIPT", "PRODUCTION", "RETURN", "IN_WAREHOUSE"})
        String source,
        @Schema(description = "The orders holding its reserved units, one entry per order; empty when none")
        List<StockPackageResponse.Hold> heldFor,
        @Schema(description = "The open work it is in, so it is busy (kltn-docs 20 BR-10); null when none",
                nullable = true)
        StockPackageResponse.OpenTask openTask
) {

    @Schema(name = "StockPackageHold", description = "An order holding units of the carton")
    public record Hold(UUID orderId, @Schema(example = "SO-20261120-0001") String orderNumber, int quantity) {
    }

    @Schema(name = "StockPackageOpenTask", description = "The open work a carton is in. id is the task to open: a "
            + "putaway task, a pick task (PICK, and CARTON_CHECK - the check session is the pick task's), or a cycle "
            + "count; number is the cycle count's number, null for the others")
    public record OpenTask(@Schema(allowableValues = {"PUTAWAY", "PICK", "CARTON_CHECK", "CYCLE_COUNT"}) String type,
                           UUID id, String number) {
    }
}
