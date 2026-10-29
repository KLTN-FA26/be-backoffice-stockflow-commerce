package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.ExpiryAlertView;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.StockAlertQueries;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * U5: lots that expire soon, or have expired, and are still in stock — the open expiry alerts, soonest first. The
 * inventory module applies the caller's warehouse limit, as on the alerts screen.
 *
 * <p><b>The quantities are the nightly check's, and say so.</b> An expiry alert carries what the nightly expiry sweep
 * counted, held units included, and a lot only gets an alert once the sweep has seen it. Reading the alerts rather
 * than scanning stock is deliberate ({@link StockAlertQueries}: the assistant and the alerts screen cannot disagree),
 * but presented as current stock the figure has people plan a clearance for units picked that morning. The row names
 * the figure for what it is, and the description points to the tools that read stock now.</p>
 *
 * <p>Each list carries its total and a truncated flag, and asking beyond the warning window gets a note: the alerts
 * end there, so a longer window finds no more lots and must not read as "nothing else expires".</p>
 */
@Component
class FindExpiringLotsTool implements AssistantTool {

    static final int MAX_ROWS = 50;
    static final int MAX_DAYS = 365;

    private static final ToolSpec SPEC = new ToolSpec("findExpiringLots",
            "Lots still in stock that expire within N days, and apart from them the lots already expired and "
                    + "waiting for a write-off, soonest first: SKU, warehouse, lot number, expiry date, days left "
                    + "(negative once expired) and the quantity the nightly expiry check last counted, held units "
                    + "included; lastCheckedAt says when it was counted, and stillHeld marks an expired lot that an "
                    + "order or production still holds, whose hold must be moved to other stock before it ships. "
                    + "Built from the stock alerts, which that check refreshes: the quantity is not live "
                    + "stock, since goods picked or received after the check are not in it, and a lot received since "
                    + "then is not listed yet. Say the figures are as of the last nightly check, and use getStockLots "
                    + "or traceLot for a lot's stock right now. The alerts only follow lots inside the expiry warning "
                    + "window (warningWindowDays in the answer): asking for more days than that finds no more lots, "
                    + "and the answer's note says so. Each list has its total and a truncated flag: when truncated is "
                    + "true, say how many of the total were left out.",
            ToolSchema.object()
                    .integer("withinDays", "Expiring within this many days from today, 0 to " + MAX_DAYS
                            + "; expired lots are listed apart")
                    .integer("limit", "How many lots to return in each list, 1 to " + MAX_ROWS + "; use " + MAX_ROWS
                            + " unless the user asks for fewer")
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
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_ALERTS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        int within = arguments.integer("withinDays", 0, MAX_DAYS);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        int window = control.expiryWarningDays();
        StockAlertQueries.ExpiryAlerts found = alerts.openExpiryAlerts(within, limit);
        String note = within <= window ? null : "Expiry alerts follow only lots within " + window + " days of their "
                + "expiry date; lots expiring later are not in this list. getStockLots shows one SKU's lots with "
                + "their expiry dates.";
        return new Answer(window, note,
                found.expiringTotal(), found.expiringTotal() > found.expiring().size(),
                found.expiring().stream().map(Row::of).toList(),
                found.expiredTotal(), found.expiredTotal() > found.expired().size(),
                found.expired().stream().map(Row::of).toList());
    }

    /**
     * @param warningWindowDays lots further from expiry than this are not followed by the alerts
     * @param note              when more days were asked for than the window; null otherwise
     * @param expiringTotal     how many lots expire within the days asked for; more than {@code expiring} holds means
     *                          some were left out, which {@code expiringTruncated} says. {@code expiredTotal} likewise
     */
    record Answer(int warningWindowDays, String note,
                  int expiringTotal, boolean expiringTruncated, List<Row> expiring,
                  int expiredTotal, boolean expiredTruncated, List<Row> expired) {
    }

    /**
     * @param quantityAtLastCheck what the nightly sweep last counted of the lot in that warehouse, held units too
     * @param lastCheckedAt       when it counted them
     * @param stillHeld           an expired lot a hold still keeps: the hold must move to other stock before it ships
     */
    record Row(String sku, String warehouseCode, String lotNumber, LocalDate expiryDate, Long daysToExpiry,
               int quantityAtLastCheck, Instant lastCheckedAt, boolean stillHeld, boolean expired) {

        static Row of(ExpiryAlertView alert) {
            return new Row(alert.sku(), alert.warehouseCode(), alert.lotNumber(), alert.expiryDate(),
                    alert.daysToExpiry(), alert.quantity(), alert.lastObservedAt(), alert.reservationImpaired(),
                    alert.expired());
        }
    }
}
