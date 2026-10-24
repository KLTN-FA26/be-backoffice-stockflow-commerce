package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.api.SupplierSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                    + "date, expected delivery date and total. Filter by status, by text in the PO number, and by "
                    + "supplier (name, code or tax code). When several suppliers match, no POs are returned: the "
                    + "candidates are, so ask which one or call again with its code. Lines are not included.",
            ToolSchema.object()
                    .choice("status", "Only POs in this status; ANY for every status", STATUSES)
                    .string("poNumber", "Text to match in the PO number; an empty string for no PO number filter")
                    .string("supplier", "The supplier's name, code or tax code, or part of it; an empty string for "
                            + "every supplier")
                    .integer("limit", "How many POs to return, 1 to " + MAX_ROWS)
                    .build());

    /** Enough to show a person the choice; a longer list means the text is too vague to pick from. */
    static final int MAX_CANDIDATES = 5;

    private final ProcurementService procurement;
    private final SupplierService suppliers;

    FindPurchaseOrdersTool(ProcurementService procurement, SupplierService suppliers) {
        this.procurement = procurement;
        this.suppliers = suppliers;
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
        String poNumber = arguments.optionalText("poNumber", 100);
        String supplierText = arguments.optionalText("supplier", 100);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        UUID supplierId = null;
        if (supplierText != null) {
            List<SupplierSummary> found = suppliers.list(0, MAX_CANDIDATES + 1, supplierText, null, null).items();
            Optional<SupplierSummary> exact = found.stream()
                    .filter(supplier -> supplier.code().equalsIgnoreCase(supplierText)).findFirst();
            if (exact.isPresent() || found.size() == 1) {
                supplierId = exact.orElse(found.getFirst()).supplierId();
            } else {
                // No match, or several: guessing one would answer "nothing ordered" about the wrong supplier.
                return new Answer(List.of(), 0, found.stream().limit(MAX_CANDIDATES)
                        .map(supplier -> new Candidate(supplier.code(), supplier.name())).toList(),
                        found.isEmpty() ? "No supplier matches '" + supplierText + "'."
                                : "Several suppliers match '" + supplierText + "'; no purchase orders were searched.");
            }
        }
        PageResponse<PurchaseOrderSummary> page = procurement.list(new ListPurchaseOrdersQuery(0, limit, supplierId,
                null, "ANY".equals(status) ? null : List.of(status), poNumber, null));
        return new Answer(page.items().stream().map(Row::of).toList(), page.totalElements(), List.of(), null);
    }

    /**
     * @param total              counts every match; {@code purchaseOrders} holds the first {@code limit}
     * @param supplierCandidates the suppliers the text matched when it did not name exactly one
     * @param note               why no purchase orders were searched; null when they were
     */
    record Answer(List<Row> purchaseOrders, long total, List<Candidate> supplierCandidates, String note) {
    }

    record Candidate(String supplierCode, String supplierName) {
    }

    record Row(String poNumber, String supplierCode, String supplierName, String warehouseCode, String status,
               LocalDate orderDate, LocalDate expectedAt, BigDecimal totalAmount, String currency) {

        static Row of(PurchaseOrderSummary po) {
            return new Row(po.poNumber(), po.supplierCode(), po.supplierName(), po.warehouseCode(), po.status(),
                    po.orderDate(), po.expectedAt(), po.totalAmount(), po.currency());
        }
    }
}
