package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockLevel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One SKU's stock per warehouse: on hand, available, reserved, allocated, available to promise. {@code levelsOf}
 * applies the caller's warehouse limit itself, so there is no extra guard.
 */
@Component
class GetStockLevelsTool implements AssistantTool {

    private static final ToolSpec SPEC = new ToolSpec("getStockLevels",
            "One SKU's stock in each warehouse the user may see: units on hand, available, reserved for orders, "
                    + "allocated to picks, and available to promise. Use it for 'how many X do we have' and 'in which "
                    + "warehouse'. It does not list lots or locations; use getStockLots for those.",
            ToolSchema.object()
                    .string("sku", "The SKU code exactly as printed, for example CUP-12OZ-WHT")
                    .build());

    private final InventoryService inventory;

    GetStockLevelsTool(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/inventory/stock-levels";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_STOCK_ITEMS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        Sku sku = new Sku(arguments.text("sku", Sku.MAX_LENGTH));
        List<Row> warehouses = inventory.levelsOf(sku).stream().map(Row::of).toList();
        return new Answer(sku.code(), warehouses);
    }

    /** {@code warehouses} is empty when no warehouse the user may see holds or expects the SKU. */
    record Answer(String sku, List<Row> warehouses) {
    }

    record Row(String warehouseCode, int onHand, int available, int reserved, int allocated, int availableToPromise) {

        static Row of(StockLevel level) {
            return new Row(level.warehouseCode(), level.onHand(), level.available(), level.reserved(),
                    level.allocated(), level.atp());
        }
    }
}
