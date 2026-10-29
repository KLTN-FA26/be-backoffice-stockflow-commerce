package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.LotTraceQueries;
import com.stockflow.inventory.api.LotTraceView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * U4: where a lot came from and which orders it went to. Scope WAREHOUSE, as on the lot screen; the inventory module
 * narrows the report to the caller's warehouses itself.
 *
 * <p><b>Every list says how long it really is.</b> A lot's orders, balances and holds are each cut, and each carries
 * its own total and truncated flag. With one flag for all three, a lot that went to 45 orders came back as 30 orders
 * and {@code truncated: true}, which reads as the recall list, and old holds alone could raise that flag on a lot
 * whose orders were all there. The orders are listed most recent first, so a cut drops the customers whose goods left
 * long ago rather than the ones whose goods may still be stopped.</p>
 *
 * <p><b>Only live holds.</b> The report lists every hold ever placed on the lot, in any status: a consumed hold is a
 * pick, already among the orders, and a released or expired one holds nothing. Passed on with their status, they read
 * as units still held for an order after the goods had left.</p>
 *
 * <p><b>Several lots share the rows out.</b> A lookup traces up to {@value #MAX_LOTS} lots, and a result longer than
 * {@link com.stockflow.assistant.internal.domain.ToolExecution#MAX_RESULT_LENGTH} reaches the model as
 * RESULT_TOO_LARGE, with nothing traced at all: {@value #MAX_ROWS} rows of each list for each of ten lots is far above
 * it. One lot gets {@value #MAX_ROWS} rows of each list, and several lots divide them, so ten lots with long lists
 * still fit; the description tells the model to ask for the SKU to see one lot in full.</p>
 */
@Component
class TraceLotTool implements AssistantTool {

    static final int MAX_LOTS = LotTraceQueries.MAX_LOTS;
    /** Rows of each list when one lot is traced; several lots divide them. */
    static final int MAX_ROWS = 30;
    /** The one status of a hold that still holds its units (inventory's {@code ReservationStatus.HELD}). */
    private static final String LIVE = "HELD";

    /** The latest first, then by number: the same lot always lists the same orders. */
    private static final Comparator<LotTraceView.OrderLeg> MOST_RECENT_FIRST = Comparator
            .comparing(LotTraceView.OrderLeg::lastAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(LotTraceView.OrderLeg::orderNumber, Comparator.nullsLast(Comparator.<String>naturalOrder()));

    private static final ToolSpec SPEC = new ToolSpec("traceLot",
            "Trace a lot by its lot number: SKU, expiry date, when and from which goods receipt, purchase order and "
                    + "supplier it came, how much was received, and how much is on hand, available and held now. "
                    + "Each lot then has three lists, each with its total and a truncated flag: orders (the customer "
                    + "orders it was picked for, with quantities, most recent first), balances (where it is now, by "
                    + "location and status) and holds (units still held on it now, for an order, or for production "
                    + "or a stock transfer when there is no order number). A lot number is unique per SKU only: a "
                    + "supplier's date code may be printed on many SKUs, each a separate lot traced separately. "
                    + "Several lots share the rows out, so each lists fewer; ask for the SKU to see one lot in full. "
                    + "When totalLots is more than the lots returned, some were not traced: say so plainly (a recall "
                    + "built on a partial list misses customers) and ask for the SKU. When ordersTruncated is true the "
                    + "customer list is incomplete: say how many of ordersTotal are not listed and that the lot screen "
                    + "lists them all, and never present the listed orders as every customer. A SKU that does not "
                    + "exist comes back as NOT_FOUND. Only warehouses the user may see.",
            ToolSchema.object()
                    .string("lotNumber", "The lot number exactly as printed on the carton or label")
                    .string("sku", "The SKU code to narrow to, exactly as the user gave it or as a tool or an "
                            + "earlier answer stated it (never composed from a product name), or an empty string for "
                            + "every SKU printed with this lot number")
                    .build());

    private final LotTraceQueries lots;
    private final InventoryService inventory;

    TraceLotTool(LotTraceQueries lots, InventoryService inventory) {
        this.lots = lots;
        this.inventory = inventory;
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
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_STOCK_MOVEMENTS, action = Action.READ,
            scope = DataScope.WAREHOUSE)
    public Answer run(ToolArguments arguments) {
        String number = arguments.text("lotNumber", 100);
        String sku = arguments.optionalText("sku", Sku.MAX_LENGTH);
        String narrowTo = sku == null ? null : KnownSkus.require(inventory, new Sku(sku)).code();
        LotTraceQueries.Traced traced = lots.traceLot(number, narrowTo, MAX_LOTS);
        int rows = Math.max(1, MAX_ROWS / Math.max(1, traced.lots().size()));
        return new Answer(number, traced.totalLots(), traced.lots().stream().map(lot -> lot(lot, rows)).toList());
    }

    /** One lot, each of its lists cut to {@code rows} and counted in full. */
    private static Lot lot(LotTraceView view, int rows) {
        List<LotTraceView.OrderLeg> orders = view.orders().stream().sorted(MOST_RECENT_FIRST).toList();
        List<LotTraceView.Hold> live = view.holds().stream().filter(hold -> LIVE.equals(hold.status())).toList();
        return new Lot(view.sku(), view.lotNumber(), view.expiryDate(), view.receivedAt(), view.origin(),
                view.goodsReceiptNumber(), view.purchaseOrderNumber(), view.supplierName(), view.receivedQuantity(),
                view.onHand(), view.available(), view.held(),
                orders.size(), orders.size() > rows, orders.stream().limit(rows)
                        .map(o -> new OrderLeg(o.orderNumber(), o.picked(), o.putBack(), o.firstAt(), o.lastAt()))
                        .toList(),
                view.balances().size(), view.balances().size() > rows, view.balances().stream().limit(rows)
                        .map(b -> new Balance(b.locationCode(), b.status(), b.onHand(), b.held())).toList(),
                live.size(), live.size() > rows, live.stream().limit(rows)
                        .map(h -> new Hold(h.orderNumber(), h.locationCode(), h.quantity())).toList());
    }

    /**
     * @param totalLots how many lots match; more than {@code lots.size()} means some were not traced
     * @param lots      empty when no lot of that number was in a warehouse the user may see
     */
    record Answer(String lotNumber, long totalLots, List<Lot> lots) {
    }

    /**
     * Each list comes with how many rows it has in full and whether some were cut.
     *
     * @param ordersTotal   the customer orders the lot left for; {@code orders} holds the most recent of them
     * @param balancesTotal the locations and statuses holding some of the lot now
     * @param holdsTotal    the live holds on the lot; closed ones are neither listed nor counted
     */
    record Lot(String sku, String lotNumber, LocalDate expiryDate, Instant receivedAt, String origin,
               String goodsReceiptNumber, String purchaseOrderNumber, String supplierName, long receivedQuantity,
               long onHand, long available, long held,
               int ordersTotal, boolean ordersTruncated, List<OrderLeg> orders,
               int balancesTotal, boolean balancesTruncated, List<Balance> balances,
               int holdsTotal, boolean holdsTruncated, List<Hold> holds) {
    }

    record Balance(String locationCode, String status, int onHand, int held) {
    }

    record OrderLeg(String orderNumber, long picked, long putBack, Instant firstAt, Instant lastAt) {
    }

    /** A live hold. {@code orderNumber} is null for a hold of a production order or a stock transfer. */
    record Hold(String orderNumber, String locationCode, int quantity) {
    }
}
