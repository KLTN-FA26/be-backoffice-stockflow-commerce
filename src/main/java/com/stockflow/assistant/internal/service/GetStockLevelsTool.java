package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockLevel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One SKU's stock per warehouse, and once for the company what may still be promised. {@code levelsOf} applies the
 * caller's warehouse limit itself, so there is no extra guard.
 *
 * <p><b>The names say what the numbers are, not what the screen's labels say.</b> Since SCRUM-152 a confirmed
 * order's checkout reservation sits on no warehouse: it is subtracted from available-to-promise company-wide, and
 * each warehouse's figure is capped at that company figure, so warehouse figures do not add up. A stock row's
 * {@code reserved} column holds allocations to picks plus production and transfer holds, and {@code StockLevel}'s
 * {@code allocated} is always 0 (the screen's fix is SCRUM-438). Passed through under their old names, a model
 * answered "6,000 available, nothing allocated" for stock already promised to customers.</p>
 *
 * <p><b>An unknown SKU is NOT_FOUND</b> ({@link KnownSkus}): nothing to promise and no warehouses is also what a real
 * SKU with no stock gets, so a mistyped code read as out of stock everywhere. The warehouse list has no cap:
 * {@code levelsOf} returns one row per warehouse, so it is as long as the business has warehouses.</p>
 */
@Component
class GetStockLevelsTool implements AssistantTool {

    private static final ToolSpec SPEC = new ToolSpec("getStockLevels",
            "One SKU's stock in each warehouse the user may see: units on hand (every status), units in available "
                    + "status, units held on stock rows (allocated to picks, issued to production or held for a "
                    + "transfer), and what that warehouse may still promise. Checkout reservations of confirmed "
                    + "orders belong to no warehouse: they are already subtracted from available to promise, and the "
                    + "warehouse figures of available to promise must not be added together. For 'how many can we "
                    + "still sell' use companyAvailableToPromise. Use it for 'how many X do we have' and 'in which "
                    + "warehouse'; for lots or locations use getStockLots. An unknown SKU comes back as NOT_FOUND: say "
                    + "the code was not found and should be checked (searchCatalog finds a product by name), never "
                    + "that it is out of stock.",
            ToolSchema.object()
                    .string("sku", KnownSkus.PARAMETER)
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
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.INVENTORY_STOCK_ITEMS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        Sku sku = KnownSkus.require(inventory, new Sku(arguments.text("sku", Sku.MAX_LENGTH)));
        List<Row> warehouses = inventory.levelsOf(sku).stream().map(Row::of).toList();
        // A user limited to particular warehouses does not see the company figure on any screen.
        Integer company = WarehouseScope.restriction().isPresent() ? null : inventory.availableToPromise(sku);
        return new Answer(sku.code(), company, warehouses);
    }

    /**
     * @param companyAvailableToPromise what the company may still promise, every warehouse together and checkout
     *                                  reservations subtracted; null for a user limited to particular warehouses
     * @param warehouses                empty when no warehouse the user may see holds or expects the SKU; never
     *                                  because the SKU is unknown, which is NOT_FOUND
     */
    record Answer(String sku, Integer companyAvailableToPromise, List<Row> warehouses) {
    }

    /**
     * @param inAvailableStatus  units in AVAILABLE status, before anything held on them is subtracted
     * @param heldOnStock        units held on this warehouse's stock rows: allocated to picks, issued to production,
     *                           held for a transfer
     * @param availableToPromise this warehouse's unheld units, capped at the company figure
     */
    record Row(String warehouseCode, int onHand, int inAvailableStatus, int heldOnStock, int availableToPromise) {

        static Row of(StockLevel level) {
            return new Row(level.warehouseCode(), level.onHand(), level.available(), level.reserved(), level.atp());
        }
    }
}
