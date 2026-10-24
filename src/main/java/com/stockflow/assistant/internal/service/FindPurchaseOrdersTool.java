package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Purchase orders by status and text — "which POs are waiting for approval", "what did we order from supplier X". The
 * procurement list applies the caller's warehouse limit in its adapter, so there is no extra guard.
 */
@Component
class FindPurchaseOrdersTool implements AssistantTool {

    static final int MAX_ROWS = 50;

    /** {@code PurchaseOrderStatus}, plus ANY. */
    static final List<String> STATUSES = List.of("ANY", "DRAFT", "PENDING_APPROVAL", "APPROVED", "CONFIRMED",
            "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED", "CANCELLED");

    private static final ToolSpec SPEC = new ToolSpec("findPurchaseOrders",
            "Purchase orders to suppliers, newest first: PO number, supplier, receiving warehouse, status, order "
                    + "date, expected delivery date and total. Filter by status and by a search text that matches "
                    + "the PO number or the supplier. Lines are not included.",
            ToolSchema.object()
                    .choice("status", "Only POs in this status; ANY for every status", STATUSES)
                    .string("search", "Text to match in the PO number or supplier name or code; an empty string "
                            + "for no text filter")
                    .integer("limit", "How many POs to return, 1 to " + MAX_ROWS)
                    .build());

    private final ProcurementService procurement;

    FindPurchaseOrdersTool(ProcurementService procurement) {
        this.procurement = procurement;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/purchase-orders";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.PURCHASE_ORDERS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String status = arguments.choice("status", STATUSES);
        String search = arguments.optionalText("search", 100);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        PageResponse<PurchaseOrderSummary> page = procurement.list(new ListPurchaseOrdersQuery(0, limit, null, null,
                "ANY".equals(status) ? null : List.of(status), search, null));
        return new Answer(page.items().stream().map(Row::of).toList(), page.totalElements());
    }

    /** {@code total} counts every match; {@code purchaseOrders} holds the first {@code limit}. */
    record Answer(List<Row> purchaseOrders, long total) {
    }

    record Row(String poNumber, String supplierCode, String supplierName, String warehouseCode, String status,
               LocalDate orderDate, LocalDate expectedAt, BigDecimal totalAmount, String currency) {

        static Row of(PurchaseOrderSummary po) {
            return new Row(po.poNumber(), po.supplierCode(), po.supplierName(), po.warehouseCode(), po.status(),
                    po.orderDate(), po.expectedAt(), po.totalAmount(), po.currency());
        }
    }
}
