package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockAvailability;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * One SKU's sellable stock by location and lot, earliest expiry first — the rows the stock screen shows for a SKU.
 * Scope WAREHOUSE, as on that screen; {@code availabilityOf} reads it.
 */
@Component
class GetStockLotsTool implements AssistantTool {

    static final int MAX_ROWS = 50;

    private static final ToolSpec SPEC = new ToolSpec("getStockLots",
            "One SKU's sellable stock (available status, not expired) by location and lot, earliest expiry first: "
                    + "location code, lot number, expiry date, units on hand, reserved, allocated and available. Only "
                    + "warehouses the user may see. Stock in quarantine, damaged or expired is not listed.",
            ToolSchema.object()
                    .string("sku", "The SKU code exactly as printed, for example CUP-12OZ-WHT")
                    .build());

    private final InventoryService inventory;

    GetStockLotsTool(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/inventory/stock-items";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_STOCK_ITEMS, action = Action.READ,
            scope = DataScope.WAREHOUSE)
    public Answer run(ToolArguments arguments) {
        Sku sku = new Sku(arguments.text("sku", Sku.MAX_LENGTH));
        List<StockAvailability> rows = inventory.availabilityOf(sku);
        return new Answer(sku.code(), rows.stream().limit(MAX_ROWS).map(Row::of).toList(), rows.size() > MAX_ROWS);
    }

    record Answer(String sku, List<Row> locations, boolean truncated) {
    }

    record Row(String locationCode, String lotNumber, LocalDate expiryDate, int onHand, int reserved, int allocated,
               int available) {

        static Row of(StockAvailability row) {
            return new Row(row.locationCode(), row.lotNumber(), row.expiryDate(), row.onHand(), row.reserved(),
                    row.allocated(), row.available());
        }
    }
}
