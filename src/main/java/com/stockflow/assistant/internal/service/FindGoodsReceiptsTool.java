package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.api.GoodsReceiptQueries;
import com.stockflow.procurement.api.GoodsReceiptSummary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * ROLE-7: receiving's and QC's queue - "Phiếu nhập nào đang chờ QC?", "Còn phiếu nào chờ cất hàng?", "GR-… tới đâu
 * rồi?" - as the goods receipts screen lists it ({@code GET /api/v1/goods-receipts}), through
 * {@link GoodsReceiptQueries}, which reads the list that screen reads. Its warehouse rule is therefore the screen's: a
 * warehouse-bound user sees only their warehouses' receipts.
 *
 * <p><b>Where a receipt stands is its status</b>: counted but not confirmed (DRAFT), waiting for or in QC until every
 * line needing it is decided (IN_QC), waiting to be put away (IN_PUTAWAY), finished (CLOSED); and waiting for a second
 * person's decision before any stock is counted in (PENDING_APPROVAL), for one of two reasons with two different
 * rejections: a count beyond a PO line's tolerance (SCRUM-477: rejecting cuts the excess and posts the rest) or goods
 * that came without a purchase order (SCRUM-478: rejecting cancels the receipt). OPEN asks for the four that are work
 * still to do. {@code withoutPurchaseOrder} tells the two apart, so that the model never calls a receipt against a PO
 * "without a PO", nor tells a manager that rejecting cancels it.
 * A row carries no lines, so it does not say which line still waits for QC or how many units are left to put away; the
 * description says so rather than let a receipt in QC read as "all of it in QC".</p>
 *
 * <p><b>What a row leaves out</b>: who received and who confirmed it (staff names) and the supplier's delivery-note
 * reference, typed by hand. The supplier is named as on the screen, a long company name cut so that fifty rows fit in
 * one result; the receipt list holds no contact of it.</p>
 */
@Component
class FindGoodsReceiptsTool implements AssistantTool {

    static final int MAX_ROWS = GoodsReceiptQueries.MAX_RECEIPTS;

    /**
     * OPEN and ANY, then every status but CONFIRMED, which a receipt leaves in the transaction that sets it: offering
     * it would only ever answer "none".
     */
    static final List<String> STATUSES = Stream.concat(Stream.of("OPEN", "ANY"),
            GoodsReceiptQueries.STATUSES.stream().filter(status -> !"CONFIRMED".equals(status))).toList();

    private static final ToolSpec SPEC = new ToolSpec("findGoodsReceipts",
            "Goods receipts (deliveries from suppliers counted in, against a purchase order or, for goods that came "
                    + "without one, against the supplier only), most recently received first: receipt number, PO "
                    + "number, withoutPurchaseOrder (true: the goods came without a purchase order, so there is no PO "
                    + "number), supplier, warehouse, status, when it was received, confirmed and closed. status: DRAFT "
                    + "counted but not confirmed yet, PENDING_APPROVAL confirmed and waiting for a second person (never "
                    + "whoever received it) to decide, with no stock counted in until then: either a line was counted "
                    + "beyond what its purchase order allows (approving posts the whole count, rejecting cuts the "
                    + "excess and posts the rest) or, when withoutPurchaseOrder is true, the goods came without a "
                    + "purchase order (approving posts them, rejecting cancels the receipt); IN_QC waiting for or in "
                    + "quality "
                    + "control (until every line that needs QC is decided), IN_PUTAWAY waiting to be put away on the "
                    + "shelves, CLOSED finished (put away, or nothing was left to put away), CANCELLED abandoned before "
                    + "confirming or rejected. OPEN asks for DRAFT, PENDING_APPROVAL, IN_QC and IN_PUTAWAY "
                    + "together, the work still to do. A row has no lines: it does not say which line still waits for "
                    + "QC or how many units are left to put away. Only receipts of the warehouses the user may see. "
                    + "Filter by status and by text in the receipt number; there is no filter by PO, supplier or "
                    + "warehouse, so when a question is about one of those, use only a list that is not truncated, and "
                    + "otherwise say what the count covers. total counts every match; when it is above the receipts "
                    + "returned (truncated), say how many were left out. findPurchaseOrders says what is still to "
                    + "arrive on a PO.",
            ToolSchema.object()
                    .choice("status", "Only receipts in this status; OPEN for DRAFT, PENDING_APPROVAL, IN_QC and "
                            + "IN_PUTAWAY; ANY for "
                            + "every status", STATUSES)
                    .string("receiptNumber", "Text in the receipt number, such as GR-20261028-0001 or part of it; an "
                            + "empty string for every receipt")
                    .integer("limit", "How many receipts to return, 1 to " + MAX_ROWS)
                    .build());

    private final GoodsReceiptQueries receipts;

    FindGoodsReceiptsTool(GoodsReceiptQueries receipts) {
        this.receipts = receipts;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/goods-receipts";
    }

    @Override
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.GOODS_RECEIPTS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String status = arguments.choice("status", STATUSES);
        String number = arguments.optionalText("receiptNumber", 30);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        List<String> statuses = switch (status) {
            case "OPEN" -> GoodsReceiptQueries.OPEN_STATUSES;
            case "ANY" -> List.of();
            default -> List.of(status);
        };
        PageResponse<GoodsReceiptSummary> page = receipts.receipts(statuses, number, limit);
        List<Row> rows = page.items().stream().map(Row::of).toList();
        return new Answer(rows, page.totalElements(), page.totalElements() > rows.size());
    }

    /**
     * @param total     every receipt that matches in the warehouses the user may see
     * @param truncated {@code total} is above the receipts returned
     */
    record Answer(List<Row> receipts, long total, boolean truncated) {
    }

    /**
     * @param supplierName as the list shows it, cut to {@value #MAX_SUPPLIER_NAME} characters and an ellipsis: enough
     *                     to tell suppliers apart, and fifty rows of long company names still fit in one result
     */
    record Row(String receiptNumber, String purchaseOrderNumber, boolean withoutPurchaseOrder, String supplierName,
               String warehouseCode, String status, Instant receivedAt, Instant confirmedAt, Instant closedAt) {

        static final int MAX_SUPPLIER_NAME = 60;

        static Row of(GoodsReceiptSummary receipt) {
            String supplier = receipt.supplierName();
            return new Row(receipt.number(), receipt.purchaseOrderNumber(), receipt.withoutPurchaseOrder(),
                    supplier != null && supplier.length() > MAX_SUPPLIER_NAME
                            ? supplier.substring(0, MAX_SUPPLIER_NAME) + "…" : supplier,
                    receipt.warehouseCode(), receipt.status(), receipt.receivedAt(), receipt.confirmedAt(),
                    receipt.closedAt());
        }
    }
}
