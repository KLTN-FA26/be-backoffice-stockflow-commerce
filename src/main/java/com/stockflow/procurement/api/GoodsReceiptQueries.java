package com.stockflow.procurement.api;

import com.stockflow.common.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Goods receipts for other modules to show, read through the list the receipt screen reads
 * ({@code GET /api/v1/goods-receipts}). The back-office assistant (ADR-0011) answers receiving's daily questions
 * with it - which receipts are still being counted, which wait to be put away - that no other {@code api} could:
 * receipts are otherwise this module's alone ({@code GoodsReceipts} drives them, and stays internal).
 *
 * <p><b>Scope.</b> The rules are that screen's: a warehouse-bound caller sees only the receipts of their warehouses
 * (BR-SEC-002). Call it inside that endpoint's guard,
 * {@code @RequiresPermission(resource = "procurement-goods-receipts", action = READ)}; outside a guard no scope is in
 * force and every warehouse is seen, as by the system.</p>
 *
 * <p>Read only. Nothing here receives, confirms or decides anything.</p>
 */
public interface GoodsReceiptQueries {

    /** The most receipts one call returns. */
    int MAX_RECEIPTS = 50;

    /**
     * Every status a receipt can be in, in the order it moves through them ({@code ck_goods_receipts_status},
     * kltn-docs 03): counted (DRAFT), confirmed and at once on to putaway (IN_PUTAWAY), put away (CLOSED), or abandoned
     * before confirming (CANCELLED). No receipt stays CONFIRMED, and there is no QC step (03 BR-07). PENDING_APPROVAL is
     * not offered: a receipt against a production order takes whole cartons within what the order asks for, so nothing
     * waits for approval (03 BR-02), and {@code ck_goods_receipts_status} no longer allows it, so no row can hold it;
     * asking for it could only ever answer "none".
     */
    List<String> STATUSES = List.of("DRAFT", "CONFIRMED", "IN_PUTAWAY", "CLOSED", "CANCELLED");

    /** The receipts still being worked on: everything but CLOSED and CANCELLED. */
    List<String> OPEN_STATUSES = List.of("DRAFT", "CONFIRMED", "IN_PUTAWAY");

    /**
     * The receipts that match, the most recently received first, as the screen lists them.
     *
     * @param statuses any of {@link #STATUSES}; empty for every status
     * @param number   text the receipt number contains ({@code GR-20261028-0001}); null or blank for any
     * @param limit    1..{@value #MAX_RECEIPTS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a status not in
     *         {@link #STATUSES} or a limit out of range
     */
    default PageResponse<GoodsReceiptSummary> receipts(List<String> statuses, String number, int limit) {
        return receipts(statuses, number, null, limit);
    }

    /**
     * {@link #receipts(List, String, int)} at one warehouse: the screen's own {@code warehouseId} filter, for the
     * assistant's work queue narrowed to the warehouse a manager runs a shift at.
     *
     * @param warehouseId one warehouse; null for every warehouse the caller may see
     * @throws com.stockflow.common.error.BusinessException as the screen: {@code OUT_OF_DATA_SCOPE} for a warehouse
     *         the caller is not assigned to
     */
    PageResponse<GoodsReceiptSummary> receipts(List<String> statuses, String number, UUID warehouseId, int limit);

    /**
     * The return receipts still being counted (DRAFT) that list a carton, the newest first: a carton that came back
     * from a customer waits there before it is quarantined (4D-2). For the assistant's carton investigation, which is
     * asked where a carton is; under the return receipts' own warehouse rule.
     */
    List<ReturnReceiptRef> openReturnReceiptsOf(UUID stockPackageId);

    /**
     * A return receipt listing a carton.
     *
     * @param quarantineLocationId the quarantine area its cartons go into once it is confirmed
     */
    record ReturnReceiptRef(UUID receiptId, String number, String status, UUID warehouseId,
                            UUID quarantineLocationId) {
    }
}
