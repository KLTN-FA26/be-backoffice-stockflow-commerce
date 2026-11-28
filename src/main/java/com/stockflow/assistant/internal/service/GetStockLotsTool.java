package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.BlindCountQueries;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockAvailability;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One SKU's sellable stock by location and lot, in picking order — the rows the stock screen shows for a SKU.
 * Scope WAREHOUSE, as on that screen; {@code availabilityOf} reads it.
 *
 * <p>A row's {@code reserved} is what is allocated on that row to an order being released for picking; the holds of
 * signed contracts' orders are SKU-level and sit on no row (CLAUDE.md, "Holding an order's stock"). So the row's free
 * units are named for what they are, and the description says they do not add up to what may be promised
 * ({@link GetStockLevelsTool} explains the same for the warehouse figures).</p>
 *
 * <p><b>Picking order is first in, first out</b> by the real receipt day, whole sealed cartons only (kltn-docs 07
 * BR-03): there is no expiry and no FEFO since the rescope.</p>
 *
 * <p><b>Rows with nothing on hand are left out, before the cap.</b> A pick that empties a bin leaves its row AVAILABLE
 * at zero, nothing deletes it, and {@code availabilityOf} returns it like any other (the {@code stock-items} screen
 * does too; only the warehouse browse screen hides such rows). In picking order an emptied older lot comes first, so
 * "which lot goes out first, and where" named a lot that had been picked out, and on a SKU received many times the
 * emptied rows fill the cap and push the real stock off the list. A row at zero holds nothing either
 * ({@code ck_stock_item_reserved_within_on_hand}), so leaving it out loses nothing.</p>
 *
 * <p><b>Blind count</b> (kltn-docs 20 BR-07). The stock-items screen keeps a row whose slot a cycle count holds but
 * shows a counter (no {@code inventory-cycle-counts:APPROVE}) no quantity on it; {@code availabilityOf} returns every
 * figure, since releases plan over it, so inventory is asked which rows to hide ({@link BlindCountQueries}, which
 * decides who is blind). Such a row keeps its location and lot, its figures are null and {@code hiddenDuringCount} is
 * true, and the description says to call them hidden during the count, never zero. It is listed even at zero on hand:
 * leaving it out would tell the counter that the books say nothing is there.</p>
 *
 * <p>The list is capped; {@code total} and {@code truncated} say how much was cut, and the description tells the model
 * to say so rather than present the first rows as all the SKU's stock. An unknown SKU is NOT_FOUND
 * ({@link KnownSkus}), never an empty list that reads as "out of stock".</p>
 */
@Component
class GetStockLotsTool implements AssistantTool {

    static final int MAX_ROWS = 50;

    private static final ToolSpec SPEC = new ToolSpec("getStockLots",
            "One SKU's sellable stock (available status) by location and lot, in picking order (first in, first "
                    + "out: the oldest receipt first, whole cartons only; nothing expires): location code, lot number, "
                    + "units on hand, units allocated on the row to an order released for picking, and units not "
                    + "allocated. Rows with nothing left on hand are not listed, a row hidden during a count excepted. "
                    + "Only warehouses the user may see. Stock in quarantine, in a staging area or damaged is not "
                    + "listed. Orders' holds are on no row, so the unallocated units do not add up to what can be "
                    + "promised: use getStockLevels for that. total counts the rows; at most " + MAX_ROWS
                    + " are listed, and when truncated is true say that only the first " + MAX_ROWS + " of total are "
                    + "shown, never that they are all the stock. An unknown SKU comes back as NOT_FOUND: say the code "
                    + "was not found and should be checked (searchCatalog finds a product by name), never that it is "
                    + "out of stock. A row with hiddenDuringCount true is in a slot a cycle count is checking, and "
                    + "this user may not see its books while it runs: its figures are null, so say they are hidden "
                    + "during the count, never zero, and leave the row out of any sum." + Packing.DESCRIPTION,
            ToolSchema.object()
                    .string("sku", KnownSkus.PARAMETER)
                    .build());

    private final InventoryService inventory;
    private final BlindCountQueries blindCount;
    private final UomConversionService units;

    /** A test's, with no slot under a count. */
    GetStockLotsTool(InventoryService inventory) {
        this(inventory, stockItemIds -> Set.of());
    }

    GetStockLotsTool(InventoryService inventory, BlindCountQueries blindCount) {
        this(inventory, blindCount, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    GetStockLotsTool(InventoryService inventory, BlindCountQueries blindCount, UomConversionService units) {
        this.inventory = inventory;
        this.blindCount = blindCount;
        this.units = units;
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
        List<StockAvailability> rows = inventory.availabilityOf(sku);
        Set<UUID> hidden = rows.isEmpty() ? Set.of()
                : blindCount.hiddenFromCaller(rows.stream().map(StockAvailability::stockPackageId).toList());
        // A hidden row stays whatever it holds: leaving it out at zero would give its books away.
        List<StockAvailability> stocked = rows.stream()
                .filter(row -> hidden.contains(row.stockPackageId()) || row.onHand() > 0)
                .toList();
        Packing packing = Packing.of(units, List.of(sku.code())).getOrDefault(sku.code(), Packing.NONE);
        return new Answer(sku.code(), packing.unit(), packing.packingUnit(), packing.unitsPerPacking(),
                stocked.size(), stocked.size() > MAX_ROWS,
                stocked.stream().limit(MAX_ROWS)
                        .map(row -> hidden.contains(row.stockPackageId()) ? Row.hidden(row) : Row.of(row, packing))
                        .toList());
    }

    /**
     * @param total     the rows with stock on hand in the warehouses the user may see, and those hidden during a count
     * @param truncated more than {@value #MAX_ROWS} rows: {@code locations} holds the first ones in picking order
     * @param locations empty when none of those warehouses holds sellable stock of the SKU
     */
    record Answer(String sku, String unit, String packingUnit, Integer unitsPerPacking, int total, boolean truncated,
                  List<Row> locations) {

        Answer(String sku, int total, boolean truncated, List<Row> locations) {
            this(sku, null, null, null, total, truncated, locations);
        }
    }

    /**
     * @param onHand            null, with {@code held}, {@code unheld} and {@code onHandPacked}, while a blind count
     *                          holds the slot and the user may not see its books
     * @param hiddenDuringCount true for such a row; null otherwise, so an ordinary row carries nothing extra
     */
    record Row(String locationCode, String lotNumber, Integer onHand, Integer held, Integer unheld,
               Packing.Packed onHandPacked, Boolean hiddenDuringCount) {

        static Row of(StockAvailability row) {
            return of(row, Packing.NONE);
        }

        static Row of(StockAvailability row, Packing packing) {
            return new Row(row.locationCode(), row.lotNumber(), row.onHand(), row.reserved(),
                    row.available(), packing.packed(row.onHand()), null);
        }

        static Row hidden(StockAvailability row) {
            return new Row(row.locationCode(), row.lotNumber(), null, null, null, null, true);
        }
    }
}
