package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.ExpiryAlertView;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.StockAlertQueries;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * U5: lots that expire soon, or have expired, and are still in stock — the open expiry alerts, soonest first. The
 * inventory module applies the caller's warehouse limit, as on the alerts screen.
 */
@Component
class FindExpiringLotsTool implements AssistantTool {

    static final int MAX_ROWS = 50;
    static final int MAX_DAYS = 365;

    private static final ToolSpec SPEC = new ToolSpec("findExpiringLots",
            "Lots still in stock that expire within N days, and apart from them the lots already expired and "
                    + "waiting for a write-off, soonest first: SKU, warehouse, lot number, expiry date, days left "
                    + "(negative once expired) and quantity, with the total of each list. Built from the stock "
                    + "alerts, which only follow lots inside the expiry warning window (see warningWindowDays in the "
                    + "answer); asking for more days than that window cannot find more lots. When a total is above "
                    + "the rows returned, say how many were left out.",
            ToolSchema.object()
                    .integer("withinDays", "Expiring within this many days from today, 0 to " + MAX_DAYS
                            + "; expired lots are listed apart")
                    .integer("limit", "How many lots to return, 1 to " + MAX_ROWS)
                    .build());

    private final StockAlertQueries alerts;
    private final InventoryControlService control;

    FindExpiringLotsTool(StockAlertQueries alerts, InventoryControlService control) {
        this.alerts = alerts;
        this.control = control;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/inventory/alerts";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_ALERTS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        int within = arguments.integer("withinDays", 0, MAX_DAYS);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        StockAlertQueries.ExpiryAlerts found = alerts.openExpiryAlerts(within, limit);
        return new Answer(control.expiryWarningDays(), found.expiring().stream().map(Row::of).toList(),
                found.expiringTotal(), found.expired().stream().map(Row::of).toList(), found.expiredTotal());
    }

    /**
     * @param warningWindowDays lots further from expiry than this are not followed by the alerts
     * @param expiringTotal     how many lots expire within the window; more than {@code expiring} holds means some
     *                          were left out. {@code expiredTotal} likewise
     */
    record Answer(int warningWindowDays, List<Row> expiring, int expiringTotal, List<Row> expired,
                  int expiredTotal) {
    }

    record Row(String sku, String warehouseCode, String lotNumber, LocalDate expiryDate, Long daysToExpiry,
               int quantity, boolean expired) {

        static Row of(ExpiryAlertView alert) {
            return new Row(alert.sku(), alert.warehouseCode(), alert.lotNumber(), alert.expiryDate(),
                    alert.daysToExpiry(), alert.quantity(), alert.expired());
        }
    }
}
