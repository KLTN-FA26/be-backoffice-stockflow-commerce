package com.stockflow.assistant.internal.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The back-office paths the work features link to (contract §0 rule 4: every item links to its screen, so the user
 * checks the source in one click). One place, so the routes are checked against the front end's list (its develop
 * branch's {@code /admin/...} pages) in one file. Where no page shows the thing, there is no link: a method answers
 * a list page with a filter, or the caller gives null, never a route made up.
 *
 * <p>Ids and codes only: a path never carries a name or anything someone typed.</p>
 */
final class WorkLinks {

    /** The dashboard, which shows the work queue itself. */
    static final String DASHBOARD = "/admin";

    private WorkLinks() {
    }

    static String ordersList() {
        return "/admin/orders";
    }

    static String orders(String status) {
        return "/admin/orders?status=" + status;
    }

    static String order(UUID orderId) {
        return "/admin/orders/" + orderId;
    }

    static String creditReviews() {
        return "/admin/orders/credit-holds";
    }

    static String quotesAwaitingPriceApproval() {
        return "/admin/quotes?priceReview=PENDING";
    }

    static String quote(UUID quoteId) {
        return "/admin/quotes/" + quoteId;
    }

    static String goodsReceipts(String status, UUID warehouseId) {
        return "/admin/receipts?status=" + status + (warehouseId == null ? "" : "&warehouseId=" + warehouseId);
    }

    static String goodsReceipt(UUID receiptId) {
        return "/admin/receipts/" + receiptId;
    }

    static String returnReceipt(UUID receiptId) {
        return "/admin/receipts/returns/" + receiptId;
    }

    static String pendingAdjustments() {
        return "/admin/inventory/adjustments?status=PENDING_APPROVAL";
    }

    static String adjustment(UUID adjustmentId) {
        return "/admin/inventory/adjustments/" + adjustmentId;
    }

    static String bankTransactionsToReview() {
        return "/admin/payment/bank-transactions?status=NEEDS_REVIEW";
    }

    static String bankTransaction(UUID transactionId) {
        return "/admin/payment/bank-transactions/" + transactionId;
    }

    static String overdueInstallments() {
        return "/admin/payment/installments?overdue=true";
    }

    static String installment(UUID installmentId) {
        return "/admin/payment/installments/" + installmentId;
    }

    static String productionOrder(UUID productionOrderId) {
        return "/admin/production-orders/" + productionOrderId;
    }

    static String einvoicesToIssue() {
        return "/admin/invoicing/einvoices?status=DRAFT&status=SUBMITTING&status=REJECTED";
    }

    /** The carton list searched by the LPN: there is no page of one carton. */
    static String carton(String lpn) {
        return "/admin/inventory/cartons?q=" + URLEncoder.encode(lpn, StandardCharsets.UTF_8);
    }

    static String pickTask(UUID taskId) {
        return "/admin/picking/" + taskId;
    }

    /** A carton check is worked on its pick task's packing page. */
    static String cartonCheck(UUID taskId) {
        return "/admin/packing/" + taskId;
    }

    static String putawayTask(UUID taskId) {
        return "/admin/putaway/" + taskId;
    }

    static String cycleCount(UUID countId) {
        return "/admin/inventory/cycle-counts/" + countId;
    }
}
