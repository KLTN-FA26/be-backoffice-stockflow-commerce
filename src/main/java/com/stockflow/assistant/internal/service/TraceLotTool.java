package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
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

    static final int MAX_LOTS = LotTraceQueries.MAX_LOTS;
    static final int MAX_ROWS = 30;

    private static final ToolSpec SPEC = new ToolSpec("traceLot",
            "Trace a lot by its lot number: SKU, expiry date, when and from which goods receipt, purchase order and "
                    + "supplier it came, how much was received, how much is on hand, available and held now and "
                    + "where, which customer orders it was picked for (with quantities) and the holds on it. A lot "
                    + "number is unique per SKU only: a supplier's date code may be printed on many SKUs, each a "
                    + "separate lot traced separately. When totalLots is more than the lots returned, some were not "
                    + "traced: say so plainly (a recall built on a partial list misses customers) and ask for the "
                    + "SKU. Only warehouses the user may see.",
            ToolSchema.object()
                    .string("lotNumber", "The lot number exactly as printed on the carton or label")
                    .string("sku", "The SKU code to narrow to, or an empty string for every SKU printed with this "
                            + "lot number")
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
        String sku = arguments.optionalText("sku", Sku.MAX_LENGTH);
        LotTraceQueries.Traced traced = lots.traceLot(number, sku == null ? null : new Sku(sku).code(), MAX_LOTS);
        return new Answer(number, traced.totalLots(), traced.lots().stream().map(TraceLotTool::lot).toList());
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

    /**
     * @param totalLots how many lots match; more than {@code lots.size()} means some were not traced
     * @param lots      empty when no lot of that number was in a warehouse the user may see
     */
    record Answer(String lotNumber, long totalLots, List<Lot> lots) {
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
