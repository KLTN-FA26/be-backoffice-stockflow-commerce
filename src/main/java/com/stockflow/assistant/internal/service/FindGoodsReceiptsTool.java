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
 * ROLE-7: receiving's queue - "Còn phiếu nào chờ cất hàng?", "GR-… tới đâu rồi?" - as the goods receipts screen lists
 * it ({@code GET /api/v1/goods-receipts}), through {@link GoodsReceiptQueries}, which reads the list that screen reads.
 * Its warehouse rule is therefore the screen's: a warehouse-bound user sees only their warehouses' receipts.
 *
 * <p><b>A receipt is against a production order</b> since the rescope (ADR-0010, kltn-docs 03): printed goods come back
 * from the producer in whole cartons, within what the order asks for, so nothing waits for approval and there is no QC
 * step (03 BR-02, BR-07). Where it stands is its status: counted but not confirmed (DRAFT), waiting to be put away
 * (IN_PUTAWAY), finished (CLOSED), abandoned (CANCELLED). PENDING_APPROVAL is not offered: no row can hold it any more
 * ({@code ck_goods_receipts_status}), so {@link GoodsReceiptQueries#STATUSES} leaves it out. A row carries no lines, so
 * it does not say how many cartons are left to put away.</p>
 *
 * <p><b>What a row leaves out</b>: who received and who confirmed it (staff names) and the delivery-note reference,
 * typed by hand. The producer is named as on the screen, a long company name cut so that fifty rows fit in one result;
 * the receipt list holds no contact of it.</p>
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
            "Goods receipts (printed goods back from a producer, counted in whole cartons against a production "
                    + "order), most recently received first: receipt number, receiptType (PRODUCTION; PURCHASE only "
                    + "for a legacy receipt from before purchasing was dropped), the production order code, the "
                    + "producer, warehouse, status, when it was received, confirmed and closed. status: DRAFT counted "
                    + "but not confirmed yet; IN_PUTAWAY confirmed, its cartons waiting to be put away on the shelves; "
                    + "CLOSED finished (every carton put away); CANCELLED abandoned before confirming. There is no QC "
                    + "step and nothing waits for approval. OPEN asks for DRAFT and IN_PUTAWAY together, the work "
                    + "still to do. "
                    + "A row has no lines: it does not say how many cartons are left to put away. Only receipts of the "
                    + "warehouses the user may see. Filter by status and by text in the receipt number; there is no "
                    + "filter by production order, producer or warehouse, so when a question is about one of those, "
                    + "use only a list that is not truncated, and otherwise say what the count covers. total counts "
                    + "every match; when it is above the receipts returned (truncated), say how many were left out.",
            ToolSchema.object()
                    .choice("status", "Only receipts in this status; OPEN for DRAFT and IN_PUTAWAY; ANY for every "
                            + "status", STATUSES)
                    .string("receiptNumber", "Text in the receipt number, in the form GR-yyyyMMdd-nnnn, or part "
                            + "of it; an empty string for every receipt")
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
     * @param producerName as the list shows it, cut to {@value #MAX_PRODUCER_NAME} characters and an ellipsis: enough
     *                     to tell producers apart, and fifty rows of long company names still fit in one result
     */
    record Row(String receiptNumber, String receiptType, String productionOrderCode, String producerName,
               String warehouseCode, String status, Instant receivedAt, Instant confirmedAt, Instant closedAt) {

        static final int MAX_PRODUCER_NAME = 60;

        static Row of(GoodsReceiptSummary receipt) {
            String producer = receipt.producerName();
            return new Row(receipt.number(), receipt.receiptType(), receipt.productionOrderCode(),
                    producer != null && producer.length() > MAX_PRODUCER_NAME
                            ? producer.substring(0, MAX_PRODUCER_NAME) + "…" : producer,
                    receipt.warehouseCode(), receipt.status(), receipt.receivedAt(), receipt.confirmedAt(),
                    receipt.closedAt());
        }
    }
}
