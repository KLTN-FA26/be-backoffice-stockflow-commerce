package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.LowStockItem;
import com.stockflow.inventory.api.ReplenishmentEligibility;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * U1: the SKUs at or below their reorder point, or below their safety stock — company-wide, because the thresholds
 * are set per SKU and not per warehouse.
 *
 * <p><b>Extra guard.</b> {@code lowStockBatch} reads no scope. The alerts screen hides these SKU-wide alerts from a
 * warehouse-limited user inside {@code StockAlertsImpl}, a service this tool does not go through, so the rule is
 * repeated here: such a user gets no rows and a note saying why.</p>
 *
 * <p><b>Only SKUs that can be bought again.</b> The alerts screen settles a low-stock alert as NOT_PURCHASABLE when the
 * product is discontinued, suspended or not active ({@code StockAlertEvaluator}); {@code lowStockBatch} does not ask.
 * Listing those SKUs sends a planner to a purchase order the form refuses (SKU_NOT_PURCHASABLE), so the same
 * {@link ReplenishmentEligibility} filter is applied here, to every page.</p>
 *
 * <p><b>Every low SKU is counted, not only the ones listed.</b> Ten rows and a bare {@code truncated}, when 23 SKUs
 * are low, read as "10 SKUs are below their reorder point", and the other 13 are never reordered. So the scan goes on
 * to the last page and {@code total} counts them all. It reads each SKU with a threshold once, which
 * {@code lowStockBatch} does anyway for a single page whenever fewer than a page are low.</p>
 *
 * <p>Usable on-hand is the threshold's measure and does not subtract checkout reservations; available to promise is
 * shown beside it, because a SKU can be above its reorder point and still have little left to sell.</p>
 */
@Component
class FindLowStockTool implements AssistantTool {

    static final int MAX_ROWS = 50;
    static final int PAGE = 100;

    private static final ToolSpec SPEC = new ToolSpec("findLowStock",
            "SKUs that can still be bought whose usable on-hand stock (available status, not expired, before "
                    + "reservations) is at or below their reorder point, or below their safety stock, with what may "
                    + "still be promised beside it. Company-wide figures: thresholds are set per SKU, not per "
                    + "warehouse, so this cannot answer for one warehouse; use getStockLevels for one SKU's stock per "
                    + "warehouse. Discontinued or suspended SKUs are left out, as on the stock alerts screen. A user "
                    + "limited to particular warehouses gets no rows. Returns SKUs in SKU order. total counts every "
                    + "low SKU: when truncated is true more are low than were listed, so say how many are low in all "
                    + "and that only some are listed; a count you give is total, never the rows you were given.",
            ToolSchema.object()
                    .integer("limit", "How many SKUs to return, 1 to " + MAX_ROWS + "; use " + MAX_ROWS
                            + " unless the user asks for fewer")
                    .build());

    private final InventoryService inventory;
    private final ObjectProvider<ReplenishmentEligibility> eligibility;

    FindLowStockTool(InventoryService inventory, ObjectProvider<ReplenishmentEligibility> eligibility) {
        this.inventory = inventory;
        this.eligibility = eligibility;
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
        return ScopeLimit.COMPANY_WIDE_ONLY;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_ALERTS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        if (WarehouseScope.restriction().isPresent()) {
            return new Answer(null, false, List.of(), "Low-stock thresholds are company-wide; a user limited to "
                    + "particular warehouses does not see them. Ask for one SKU's stock per warehouse instead.");
        }
        List<LowStockItem> shown = new ArrayList<>();
        int total = 0;
        String after = null;
        List<LowStockItem> page;
        do {
            page = inventory.lowStockBatch(after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            Set<String> buyable = purchasable(page.stream().map(LowStockItem::sku).toList());
            for (LowStockItem item : page) {
                if (buyable.contains(item.sku())) {
                    total++;
                    if (shown.size() < limit) {
                        shown.add(item);
                    }
                }
            }
            after = page.get(page.size() - 1).sku();
        } while (page.size() == PAGE);
        Map<Sku, Integer> promisable = inventory.availableToPromise(
                shown.stream().map(item -> new Sku(item.sku())).toList());
        return new Answer(total, total > shown.size(),
                shown.stream().map(item -> Row.of(item, promisable.getOrDefault(new Sku(item.sku()), 0))).toList(),
                null);
    }

    /** Without a product module (a module test) every SKU counts as purchasable, as StockAlertEvaluator does. */
    private Set<String> purchasable(List<String> skus) {
        ReplenishmentEligibility owner = eligibility.getIfAvailable();
        return owner == null ? Set.copyOf(skus) : owner.replenishable(skus);
    }

    /**
     * @param total     every low SKU that can be bought; null when the list is withheld, which {@code note} explains,
     *                  so a withheld list never reads as "none is low"
     * @param truncated more SKUs are low than were returned
     * @param note      why the list is empty when that is not because nothing is low
     */
    record Answer(Integer total, boolean truncated, List<Row> skus, String note) {
    }

    record Row(String sku, long usableOnHand, int availableToPromise, Integer reorderPoint, Integer safetyStock,
               boolean atOrBelowReorderPoint, boolean belowSafetyStock) {

        static Row of(LowStockItem item, int availableToPromise) {
            return new Row(item.sku(), item.usableOnHand(), availableToPromise, item.reorderPoint(),
                    item.safetyStock(), item.reorderRequired(), item.belowSafetyStock());
        }
    }
}
