package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "StockPackageDetail", description = "One carton looked up by its LPN, with the stock rows in it (SCRUM-473)")
public record StockPackageDetailResponse(
        @Schema(example = "LPN-20261026-000001") String lpn,
        String sku,
        @Schema(description = "The packing unit the carton was made in", example = "CARTON") String unitOfMeasure,
        @Schema(description = "What it was made to hold: its unit's factor then") int nominalQty,
        @Schema(description = "When it was counted in at receiving; null until counted. A counted carton stands for "
                + "nominalQty units", nullable = true) Instant countedAt,
        @Schema(description = "Of the stock it holds (one lot at most); null when it holds nothing") String lotNumber,
        @Schema(description = "What is left in it: the sum of its stock rows; 0 before it is received") int onHand,
        @Schema(description = "Of onHand, the units held for orders") int reserved,
        @Schema(description = "RECEIVING, IN_STOCK, CONSUMED, LOST or VOID") String status,
        @Schema(example = "HCM-A01-1-A") String locationCode,
        @Schema(description = "The goods receipt line it arrived on; null for one made in the warehouse")
        UUID goodsReceiptLineId,
        @Schema(description = "When it was emptied, lost or voided") Instant closedAt,
        @Schema(description = "A return receipt found it opened or empty (WF-05 Q-18): never sold again - it stays "
                + "CONSUMED, its goods destroyed (in quarantine to be written off, for one received before)")
        boolean openedOnReturn,
        @Schema(description = "When a return receipt found it opened or empty; null when it never came back so")
        Instant returnOpenedAt,
        @Schema(description = "Whose goods it is after it last came back on a return request (WF-05 Q-4; audit r3 "
                + "RAD-4). PENDING: its return's refund is not decided - release and write-off answer 409 "
                + "STOCK_PACKAGE_DECISION_PENDING, so do not offer them; show 'Chờ duyệt hoàn tiền - "
                + "{returnRmaNumber}'. COMPANY: decidable as any quarantined carton. CUSTOMER_HELD / CUSTOMER_SENT_BACK / "
                + "CUSTOMER_DESTROYED: the customer's, out of the books (GET /inventory/stock-packages/"
                + "customer-custody). Null: never came back on a return request",
                allowableValues = {"PENDING", "COMPANY", "CUSTOMER_HELD", "CUSTOMER_SENT_BACK", "CUSTOMER_DESTROYED"})
        String returnOwnership,
        @Schema(description = "The return request it last came back on; null with returnOwnership")
        UUID returnRequestId,
        @Schema(description = "That return's RMA number", example = "RMA-20261120-0003") String returnRmaNumber,
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
        StockPackageResponse.OpenTask openTask,
        @Schema(description = "Its stock rows holding something, oldest receipt first") List<Content> contents
) {

    @Schema(name = "StockPackageContent", description = "One stock row inside a carton")
    public record Content(
            String lotNumber,
            Instant receivedAt,
            @Schema(description = "INBOUND, AVAILABLE, QUARANTINE, BLOCKED, DAMAGED or EXPIRED") String status,
            int onHand,
            int reserved
    ) {
    }
}
