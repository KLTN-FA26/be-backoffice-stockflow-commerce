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
            "Lots still in stock that expire within N days or have already expired, soonest first: SKU, warehouse, "
                    + "lot number, expiry date, days left (negative once expired) and quantity. Built from the stock "
                    + "alerts, which only follow lots inside the expiry warning window (see warningWindowDays in the "
                    + "answer); asking for more days than that window cannot find more lots.",
            ToolSchema.object()
                    .integer("withinDays", "Expiring within this many days from today, 0 to " + MAX_DAYS
                            + "; expired lots are always included")
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
        List<ExpiryAlertView> matching = alerts.openExpiryAlerts(StockAlertQueries.MAX_ALERTS).stream()
                .filter(alert -> alert.daysToExpiry() == null || alert.daysToExpiry() <= within)
                .toList();
        return new Answer(control.expiryWarningDays(),
                matching.stream().limit(limit).map(Row::of).toList(), matching.size() > limit);
    }

    /** @param warningWindowDays lots further from expiry than this are not followed by the alerts */
    record Answer(int warningWindowDays, List<Row> lots, boolean truncated) {
    }

    record Row(String sku, String warehouseCode, String lotNumber, LocalDate expiryDate, Long daysToExpiry,
               int quantity, boolean expired) {

        static Row of(ExpiryAlertView alert) {
            return new Row(alert.sku(), alert.warehouseCode(), alert.lotNumber(), alert.expiryDate(),
                    alert.daysToExpiry(), alert.quantity(), alert.expired());
        }
    }
}
