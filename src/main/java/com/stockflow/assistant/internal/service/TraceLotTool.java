package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.LotTraceQueries;
import com.stockflow.inventory.api.LotTraceView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * U4: where a lot came from and which orders it went to. Scope WAREHOUSE, as on the lot screen; the inventory module
 * narrows the report to the caller's warehouses itself.
 */
@Component
class TraceLotTool implements AssistantTool {

    static final int MAX_LOTS = 5;
    static final int MAX_ROWS = 30;

    private static final ToolSpec SPEC = new ToolSpec("traceLot",
            "Trace a lot by its lot number: SKU, expiry date, when and from which goods receipt, purchase order and "
                    + "supplier it came, how much was received, how much is on hand, available and held now and "
                    + "where, which customer orders it was picked for (with quantities) and the holds on it. Two "
                    + "receipts may share a lot number; each is traced separately. Only warehouses the user may see.",
            ToolSchema.object()
                    .string("lotNumber", "The lot number exactly as printed on the carton or label")
                    .build());

    private final LotTraceQueries lots;

    TraceLotTool(LotTraceQueries lots) {
        this.lots = lots;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/inventory/lots/{lotId}";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_STOCK_MOVEMENTS, action = Action.READ,
            scope = DataScope.WAREHOUSE)
    public Answer run(ToolArguments arguments) {
        String number = arguments.text("lotNumber", 100);
        return new Answer(number, lots.traceLot(number, MAX_LOTS).stream().map(TraceLotTool::lot).toList());
    }

    private static Lot lot(LotTraceView view) {
        return new Lot(view.sku(), view.lotNumber(), view.expiryDate(), view.receivedAt(), view.origin(),
                view.goodsReceiptNumber(), view.purchaseOrderNumber(), view.supplierName(), view.receivedQuantity(),
                view.onHand(), view.available(), view.held(),
                view.balances().stream().limit(MAX_ROWS)
                        .map(b -> new Balance(b.locationCode(), b.status(), b.onHand(), b.held())).toList(),
                view.orders().stream().limit(MAX_ROWS)
                        .map(o -> new OrderLeg(o.orderNumber(), o.picked(), o.putBack(), o.firstAt(), o.lastAt()))
                        .toList(),
                view.holds().stream().limit(MAX_ROWS)
                        .map(h -> new Hold(h.orderNumber(), h.locationCode(), h.quantity(), h.status())).toList(),
                view.balances().size() > MAX_ROWS || view.orders().size() > MAX_ROWS
                        || view.holds().size() > MAX_ROWS);
    }

    /** {@code lots} is empty when no lot of that number was in a warehouse the user may see. */
    record Answer(String lotNumber, List<Lot> lots) {
    }

    record Lot(String sku, String lotNumber, LocalDate expiryDate, Instant receivedAt, String origin,
               String goodsReceiptNumber, String purchaseOrderNumber, String supplierName, long receivedQuantity,
               long onHand, long available, long held, List<Balance> balances, List<OrderLeg> orders,
               List<Hold> holds, boolean truncated) {
    }

    record Balance(String locationCode, String status, int onHand, int held) {
    }

    record OrderLeg(String orderNumber, long picked, long putBack, Instant firstAt, Instant lastAt) {
    }

    /** {@code orderNumber} is null for a production hold. */
    record Hold(String orderNumber, String locationCode, int quantity, String status) {
    }
}
