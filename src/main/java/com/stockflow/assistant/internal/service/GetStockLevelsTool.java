package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * One SKU's stock per warehouse, and once for the company what may still be promised. {@code levelsOf} and
 * {@code orderHoldsByWarehouse} apply the caller's warehouse limit themselves, so there is no extra guard.
 *
 * <p><b>The names say what the numbers are, not what the screen's labels say.</b> Three kinds of hold take stock off
 * what a warehouse may promise, and inventory keeps them apart:</p>
 * <ul>
 *   <li><b>allocated to picks</b> ({@code StockLevel.allocated}): a released order's cartons on a bin and lot, held on
 *       the stock rows until picked;</li>
 *   <li><b>other holds on stock rows</b> ({@code StockLevel.reserved}, which excludes the allocations): row-level holds
 *       left from before SKU holds, normally none;</li>
 *   <li><b>held for orders</b> ({@code InventoryService.orderHoldsByWarehouse}): what a signed contract's order holds
 *       by SKU at its shipping warehouse before release (4B-7, kltn-docs 20 BR-17). It sits on no stock row, so it is
 *       in neither figure above, yet it already comes off that warehouse's available to promise.</li>
 * </ul>
 *
 * <p>{@code heldOnStock} is the first two together and {@code allocatedToPicks} the first alone. Passing
 * {@code reserved} through as "held" read zero for a warehouse whose stock was all allocated or promised, and nothing
 * then explained the gap between units in available status and what may be promised. Each warehouse's figure is also
 * capped at what is left company-wide once every order hold is counted, an old one naming no warehouse included, so
 * the warehouse figures must not be added together. There are no production or transfer holds since the rescope:
 * goods are printed outside the system and stock never changes warehouse.</p>
 *
 * <p><b>An unknown SKU is NOT_FOUND</b> ({@link KnownSkus}): nothing to promise and no warehouses is also what a real
 * SKU with no stock gets, so a mistyped code read as out of stock everywhere. The warehouse list has no cap:
 * {@code levelsOf} returns one row per warehouse, so it is as long as the business has warehouses.</p>
 */
@Component
class GetStockLevelsTool implements AssistantTool {

    private static final ToolSpec SPEC = new ToolSpec("getStockLevels",
            "One SKU's stock in each warehouse the user may see: units on hand (every status), units in available "
                    + "status, units held on that warehouse's stock rows (heldOnStock; of them allocatedToPicks, the "
                    + "cartons of released orders waiting to be picked), units that orders of signed contracts hold "
                    + "at that warehouse before release (heldForOrders, on no stock row), and what that warehouse may "
                    + "still promise: whole free cartons less heldForOrders, never more than the company figure, so "
                    + "the warehouse figures of available to promise must not be added together. For 'how many can we "
                    + "still sell' use companyAvailableToPromise, which already subtracts every order's hold. Use it "
                    + "for 'how many X do we have' and 'in which warehouse'; for lots or locations use getStockLots. "
                    + "An unknown SKU comes back as NOT_FOUND: say the code was not found and should be checked "
                    + "(searchCatalog finds a product by name), never that it is out of stock." + Packing.DESCRIPTION,
            ToolSchema.object()
                    .string("sku", KnownSkus.PARAMETER)
                    .build());

    private final InventoryService inventory;
    private final UomConversionService units;

    GetStockLevelsTool(InventoryService inventory) {
        this(inventory, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    GetStockLevelsTool(InventoryService inventory, UomConversionService units) {
        this.inventory = inventory;
        this.units = units;
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
        Packing packing = Packing.of(units, List.of(sku.code())).getOrDefault(sku.code(), Packing.NONE);
        Map<String, Integer> forOrders = inventory.orderHoldsByWarehouse(sku);
        List<Row> warehouses = inventory.levelsOf(sku).stream()
                .map(level -> Row.of(level, forOrders.getOrDefault(level.warehouseCode(), 0), packing))
                .toList();
        // A user limited to particular warehouses does not see the company figure on any screen.
        Integer company = WarehouseScope.restriction().isPresent() ? null : inventory.availableToPromise(sku);
        return new Answer(sku.code(), packing.unit(), packing.packingUnit(), packing.unitsPerPacking(), company,
                company == null ? null : packing.packed(company), warehouses);
    }

    /**
     * @param companyAvailableToPromise what the company may still promise, every warehouse together and every order's
     *                                  hold subtracted; null for a user limited to particular warehouses
     * @param warehouses                empty when no warehouse the user may see holds or expects the SKU; never
     *                                  because the SKU is unknown, which is NOT_FOUND
     */
    record Answer(String sku, String unit, String packingUnit, Integer unitsPerPacking,
                  Integer companyAvailableToPromise, Packing.Packed companyAvailableToPromisePacked,
                  List<Row> warehouses) {

        Answer(String sku, Integer companyAvailableToPromise, List<Row> warehouses) {
            this(sku, null, null, null, companyAvailableToPromise, null, warehouses);
        }
    }

    /**
     * @param inAvailableStatus  units in AVAILABLE status, before anything held on them is subtracted
     * @param heldOnStock        units held on this warehouse's stock rows: {@code StockLevel.reserved} and
     *                           {@code allocated} together, which the level keeps apart
     * @param allocatedToPicks   of {@code heldOnStock}, the units a release placed on a bin and lot for picking
     * @param heldForOrders      units orders of signed contracts hold at this warehouse by SKU, on no stock row
     * @param availableToPromise this warehouse's free whole cartons less {@code heldForOrders}, capped at the company
     *                           figure
     */
    record Row(String warehouseCode, int onHand, int inAvailableStatus, int heldOnStock, int allocatedToPicks,
               int heldForOrders, int availableToPromise,
               Packing.Packed onHandPacked, Packing.Packed availableToPromisePacked) {

        Row(String warehouseCode, int onHand, int inAvailableStatus, int heldOnStock, int availableToPromise) {
            this(warehouseCode, onHand, inAvailableStatus, heldOnStock, 0, 0, availableToPromise, null, null);
        }

        static Row of(StockLevel level) {
            return of(level, 0, Packing.NONE);
        }

        static Row of(StockLevel level, int heldForOrders, Packing packing) {
            return new Row(level.warehouseCode(), level.onHand(), level.available(),
                    level.reserved() + level.allocated(), level.allocated(), heldForOrders, level.atp(),
                    packing.packed(level.onHand()), packing.packed(level.atp()));
        }
    }
}
