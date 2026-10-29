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
 * One SKU's sellable stock by location and lot, in picking order — the rows the stock screen shows for a SKU.
 * Scope WAREHOUSE, as on that screen; {@code availabilityOf} reads it.
 *
 * <p>A row's {@code reserved} is what is held on that row (allocations to picks, production and transfer holds) and
 * its {@code allocated} is always 0; checkout reservations sit on no row (SCRUM-152). So the row's free units are
 * named for what they are, and the description says they do not add up to what may be promised
 * ({@link GetStockLevelsTool} explains the same for the warehouse figures).</p>
 *
 * <p><b>Rows with nothing on hand are left out, before the cap.</b> A pick that empties a bin leaves its row AVAILABLE
 * at zero, nothing deletes it, and {@code availabilityOf} returns it like any other (the {@code stock-items} screen
 * does too; only the warehouse browse screen hides such rows). In picking order an emptied lot that expires sooner
 * comes first, so "which lot expires first, and where" named a lot that had been picked out, and on a SKU received
 * many times the emptied rows fill the cap and push the real stock off the list. A row at zero holds nothing either
 * ({@code ck_stock_item_reserved_within_on_hand}), so leaving it out loses nothing.</p>
 *
 * <p>The list is capped; {@code total} and {@code truncated} say how much was cut, and the description tells the model
 * to say so rather than present the first rows as all the SKU's stock. An unknown SKU is NOT_FOUND
 * ({@link KnownSkus}), never an empty list that reads as "out of stock".</p>
 */
@Component
class GetStockLotsTool implements AssistantTool {

    static final int MAX_ROWS = 50;

    private static final ToolSpec SPEC = new ToolSpec("getStockLots",
            "One SKU's sellable stock (available status, not expired) by location and lot, in picking order "
                    + "(earliest expiry first; oldest receipt first for a SKU picked first in, first out): location "
                    + "code, lot number, expiry date, units on hand, units held on the row (allocated to a pick, "
                    + "production or a transfer) and units not held. Rows with nothing left on hand are not listed. "
                    + "Only warehouses the user may see. Stock in quarantine, damaged or expired is not listed. "
                    + "Checkout reservations of confirmed orders are on no row, so the unheld units do not add up to "
                    + "what can be promised: use getStockLevels for that. total counts the rows; at most " + MAX_ROWS
                    + " are listed, and when truncated is true say that only the first " + MAX_ROWS + " of total are "
                    + "shown, never that they are all the stock. An unknown SKU comes back as NOT_FOUND: say the code "
                    + "was not found and should be checked (searchCatalog finds a product by name), never that it is "
                    + "out of stock.",
            ToolSchema.object()
                    .string("sku", KnownSkus.PARAMETER)
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
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_STOCK_ITEMS, action = Action.READ,
            scope = DataScope.WAREHOUSE)
    public Answer run(ToolArguments arguments) {
        Sku sku = KnownSkus.require(inventory, new Sku(arguments.text("sku", Sku.MAX_LENGTH)));
        List<StockAvailability> stocked = inventory.availabilityOf(sku).stream()
                .filter(row -> row.onHand() > 0)
                .toList();
        return new Answer(sku.code(), stocked.size(), stocked.size() > MAX_ROWS,
                stocked.stream().limit(MAX_ROWS).map(Row::of).toList());
    }

    /**
     * @param total     the rows with stock on hand in the warehouses the user may see
     * @param truncated more than {@value #MAX_ROWS} rows: {@code locations} holds the first ones in picking order
     * @param locations empty when none of those warehouses holds sellable stock of the SKU
     */
    record Answer(String sku, int total, boolean truncated, List<Row> locations) {
    }

    record Row(String locationCode, String lotNumber, LocalDate expiryDate, int onHand, int held, int unheld) {

        static Row of(StockAvailability row) {
            return new Row(row.locationCode(), row.lotNumber(), row.expiryDate(), row.onHand(), row.reserved(),
                    row.available());
        }
    }
}
