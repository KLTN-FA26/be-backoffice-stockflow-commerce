package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.LowStockItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * U1: the SKUs at or below their reorder point, or below their safety stock — company-wide, because the thresholds
 * are set per SKU and not per warehouse.
 *
 * <p><b>Extra guard.</b> {@code lowStockBatch} reads no scope. The alerts screen hides these SKU-wide alerts from a
 * warehouse-limited user inside {@code StockAlertsImpl}, a service this tool does not go through, so the rule is
 * repeated here: such a user gets no rows and a note saying why.</p>
 */
@Component
class FindLowStockTool implements AssistantTool {

    static final int MAX_ROWS = 50;

    private static final ToolSpec SPEC = new ToolSpec("findLowStock",
            "SKUs whose usable on-hand stock is at or below their reorder point, or below their safety stock. "
                    + "Company-wide figures: thresholds are set per SKU, not per warehouse, so this cannot answer for "
                    + "one warehouse; use getStockLevels for one SKU's stock per warehouse. A user limited to "
                    + "particular warehouses gets no rows, as on the stock alerts screen. Returns SKUs in SKU order.",
            ToolSchema.object()
                    .integer("limit", "How many SKUs to return, 1 to " + MAX_ROWS)
                    .build());

    private final InventoryService inventory;

    FindLowStockTool(InventoryService inventory) {
        this.inventory = inventory;
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
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        if (WarehouseScope.restriction().isPresent()) {
            return new Answer(List.of(), false, "Low-stock thresholds are company-wide; a user limited to particular "
                    + "warehouses does not see them. Ask for one SKU's stock per warehouse instead.");
        }
        List<LowStockItem> found = inventory.lowStockBatch(null, limit + 1);
        return new Answer(found.stream().limit(limit).map(Row::of).toList(), found.size() > limit, null);
    }

    /**
     * @param truncated more SKUs are low than were returned
     * @param note      why the list is empty when that is not because nothing is low
     */
    record Answer(List<Row> skus, boolean truncated, String note) {
    }

    record Row(String sku, long usableOnHand, Integer reorderPoint, Integer safetyStock, boolean atOrBelowReorderPoint,
               boolean belowSafetyStock) {

        static Row of(LowStockItem item) {
            return new Row(item.sku(), item.usableOnHand(), item.reorderPoint(), item.safetyStock(),
                    item.reorderRequired(), item.belowSafetyStock());
        }
    }
}
