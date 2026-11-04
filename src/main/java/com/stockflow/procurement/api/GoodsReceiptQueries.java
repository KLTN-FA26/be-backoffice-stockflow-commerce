package com.stockflow.procurement.api;

import com.stockflow.common.api.PageResponse;

import java.util.List;

/**
 * Goods receipts for other modules to show, read through the list the receipt screen reads
 * ({@code GET /api/v1/goods-receipts}). The back-office assistant (ADR-0010) answers receiving's and QC's daily
 * questions with it - which receipts wait for QC, which wait to be put away - that no other {@code api} could: receipts
 * are otherwise this module's alone ({@code GoodsReceipts} drives them, and stays internal).
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
     * Every status, in the order a receipt moves through them ({@code ck_goods_receipts_status}, docs 03 §5.1): counted
     * (DRAFT), confirmed and at once on to QC (IN_QC) or to putaway (IN_PUTAWAY), put away (CLOSED), or abandoned before
     * confirming (CANCELLED). No receipt stays CONFIRMED.
     */
    List<String> STATUSES = List.of("DRAFT", "CONFIRMED", "IN_QC", "IN_PUTAWAY", "CLOSED", "CANCELLED");

    /** The receipts still being worked on: everything but CLOSED and CANCELLED. */
    List<String> OPEN_STATUSES = List.of("DRAFT", "CONFIRMED", "IN_QC", "IN_PUTAWAY");

    /**
     * The receipts that match, the most recently received first, as the screen lists them.
     *
     * @param statuses any of {@link #STATUSES}; empty for every status
     * @param number   text the receipt number contains ({@code GR-20261028-0001}); null or blank for any
     * @param limit    1..{@value #MAX_RECEIPTS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a status not in
     *         {@link #STATUSES} or a limit out of range
     */
    PageResponse<GoodsReceiptSummary> receipts(List<String> statuses, String number, int limit);
}
