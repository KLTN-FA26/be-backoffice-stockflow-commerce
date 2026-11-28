package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One SKU's stock per warehouse, and once for the company what may still be promised. {@code levelsOf} and
 * {@code orderHoldsByWarehouse} apply the caller's warehouse limit themselves, so there is no extra guard.
 *
 * <p><b>The names say what the numbers are, not what the screen's labels say.</b> Three kinds of hold take stock off
 * what a warehouse may promise, and inventory keeps them apart:</p>
 * <ul>
 *   <li><b>allocated to picks</b> ({@code StockLevel.allocated}): a released order's cartons, held until picked;</li>
 *   <li><b>other holds on stock</b> ({@code StockLevel.reserved}, which excludes the allocations): row-level holds
 *       left from before SKU holds, normally none;</li>
 *   <li><b>held for orders</b> ({@code InventoryService.orderHoldsByWarehouse}): what a signed contract's order holds
 *       by SKU at its shipping warehouse before release (4B-7, kltn-docs 20 BR-17). It sits on no carton, so it is in
 *       neither figure above, yet it already comes off that warehouse's available to promise.</li>
 * </ul>
 *
 * <p>{@code heldOnStock} is the first two together and {@code allocatedToPicks} the first alone. Each warehouse's
 * figure is also capped at what is left company-wide once every order hold is counted, so the warehouse figures must
 * not be added together. There are no production or transfer holds since the rescope.</p>
 *
 * <p><b>Cartons are counted, never divided out of units</b> (ADR-0011 step 3e). The warehouse handles whole cartons
 * only, and {@link StockLevel} carries each figure again as cartons counted one by one. Units divided by the carton's
 * standard count were wrong as soon as cartons of two counts of the SKU stood in one warehouse: 2 cartons of 50 and 1
 * of 20 are 120 units, which "2 thùng 50 và 20 cái lẻ" misreads as an opened carton that does not exist. So every
 * warehouse figure is given in units and in cartons as inventory counted them; {@link Packing} is kept only to name the
 * carton ({@code packingUnit}, {@code unitsPerPacking}) and for the company figure, which is asked of inventory in
 * cartons of the SKU's standard count ({@code InventoryService.cartonsToPromise}): cartons of another count are not in
 * it, as they never serve a line quoted in the standard one. {@code heldForOrders} stays in units: inventory gives it
 * so, and dividing it would repeat the mistake.</p>
 *
 * <p><b>An unknown SKU is NOT_FOUND</b> ({@link KnownSkus}): nothing to promise and no warehouses is also what a real
 * SKU with no stock gets, so a mistyped code read as out of stock everywhere. The warehouse list has no cap:
 * {@code levelsOf} returns one row per warehouse, so it is as long as the business has warehouses.</p>
 *
 * <p><b>A catalogue variant holds no stock</b> (V20270105000100): the goods are customers' own SKUs drawn on it
 * ({@code product :: api CustomerSkus}). {@code searchCatalog} finds the variant by name; answering its code with
 * NOT_FOUND told the user "code not found" while its goods stood in stock under the customers' SKUs. Given a variant,
 * the tool says so ({@code catalogueVariant}) and lists the stock of the customers' SKUs drawn on it that inventory
 * holds, each with its warehouses as for a SKU and under the same warehouse limit, at most
 * {@value #MAX_CUSTOMER_SKUS} with the total. Only a SKU with a warehouse the user may see is listed or counted: a
 * warehouse manager asking about a variant is not told of customers' SKUs stocked only in other warehouses, as rows
 * with no warehouse, and an inventory item opened for an order with no stock yet is not stock. Finding them reads each
 * candidate's warehouses ({@code levelsOf} has no batch form), at most {@value #LEVELS_READ} of them; the company
 * figures of the listed ones are read in one query each ({@code availableToPromise} and {@code cartonsToPromise} over
 * the SKUs). Whose SKU it is is named, through the conversation's alias
 * ({@link CustomerName}), only to a caller who may read customers, as the order tools name a customer; the SKU's code
 * is shown to everyone who may read stock, as the stock screens show it.</p>
 */
@Component
class GetStockLevelsTool implements AssistantTool {

    private static final Logger log = LoggerFactory.getLogger(GetStockLevelsTool.class);

    private static final ToolSpec SPEC = new ToolSpec("getStockLevels",
            "One SKU's stock in each warehouse the user may see, each figure in units and again in cartons as counted "
                    + "(...Cartons): on hand (every status), in available status, held on that warehouse's stock "
                    + "(heldOnStock; of them allocatedToPicks, the cartons of released orders waiting to be picked), "
                    + "units that orders of signed contracts hold at that warehouse before release (heldForOrders, "
                    + "units only, on no carton), and what that warehouse may still promise: whole free cartons less "
                    + "heldForOrders, never more than the company figure, so the warehouse figures of available to "
                    + "promise must not be added together. For 'how many can we still sell' use "
                    + "companyAvailableToPromise (units), which already subtracts every order's hold, and "
                    + "companyCartonsToPromise: cartons of the SKU's standard count (unitsPerPacking) still to promise; "
                    + "cartons of another count are not in it. Quote the ...Cartons figures as they are; never divide "
                    + "units by unitsPerPacking yourself: cartons of two counts may stand in one warehouse. "
                    + "packingUnit names the carton. Use it for 'how many X do we have' and 'in which warehouse'; for "
                    + "lots or locations use getStockLots. A catalogue variant (the SKUs searchCatalog returns) holds "
                    + "no stock of its own: given one, catalogueVariant is true and customerSkus lists the stock of the "
                    + "customers' own SKUs drawn on it, each with its warehouses as above and its customer; say the "
                    + "stock is under those SKUs, never that the code was not found or is out of stock. An unknown SKU "
                    + "comes back as NOT_FOUND: say the code was not found and should be checked (searchCatalog finds "
                    + "a product by name), never that it is out of stock.",
            ToolSchema.object()
                    .string("sku", KnownSkus.PARAMETER)
                    .build());

    /** Customer SKUs of a catalogue variant listed with their stock; the total counts them all. */
    static final int MAX_CUSTOMER_SKUS = 10;

    /** Customer SKUs of a variant read to find the stocked ones: more than one variant is ever drawn on. */
    static final int DRAWN_READ = 100;

    /**
     * Stocked customer SKUs whose warehouses are read to find the ones the user may see: a few times what is listed,
     * so a warehouse-bound user still gets theirs when most stand elsewhere, and never one query per drawn SKU.
     */
    static final int LEVELS_READ = 3 * MAX_CUSTOMER_SKUS;

    private final InventoryService inventory;
    private final UomConversionService units;
    private final CustomerSkus customerSkus;
    private final CustomerService customers;
    private final CurrentUserProvider currentUsers;

    GetStockLevelsTool(InventoryService inventory) {
        this(inventory, null, null, null, null);
    }

    GetStockLevelsTool(InventoryService inventory, UomConversionService units) {
        this(inventory, units, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    GetStockLevelsTool(InventoryService inventory, UomConversionService units, CustomerSkus customerSkus,
                       CustomerService customers, CurrentUserProvider currentUsers) {
        this.inventory = inventory;
        this.units = units;
        this.customerSkus = customerSkus;
        this.customers = customers;
        this.currentUsers = currentUsers;
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
        Sku asked = new Sku(arguments.text("sku", Sku.MAX_LENGTH));
        Sku sku;
        try {
            sku = KnownSkus.require(inventory, asked);
        } catch (NotFoundException unknown) {
            return ofVariant(asked).orElseThrow(() -> unknown);
        }
        Packing packing = Packing.of(units, List.of(sku.code())).getOrDefault(sku.code(), Packing.NONE);
        Stock stock = stockOf(sku, packing);
        return new Answer(sku.code(), packing.packingUnit(), packing.unitsPerPacking(), stock.company(),
                stock.companyCartons(), stock.warehouses(), null, null, null, null);
    }

    /** One SKU's warehouses, and the company figures for a user not limited to particular warehouses. */
    private Stock stockOf(Sku sku, Packing packing) {
        List<Row> warehouses = rowsOf(sku, inventory.levelsOf(sku));
        // A user limited to particular warehouses does not see the company figure on any screen.
        boolean limited = WarehouseScope.restriction().isPresent();
        Integer company = limited ? null : inventory.availableToPromise(sku);
        Integer companyCartons = limited ? null : companyCartons(sku, packing);
        return new Stock(company, companyCartons, warehouses);
    }

    /** The rows of a SKU's levels, each with what orders hold at that warehouse. */
    private List<Row> rowsOf(Sku sku, List<StockLevel> levels) {
        Map<String, Integer> forOrders = inventory.orderHoldsByWarehouse(sku);
        return levels.stream().map(level -> Row.of(level, forOrders.getOrDefault(level.warehouseCode(), 0))).toList();
    }

    private record Stock(Integer company, Integer companyCartons, List<Row> warehouses) {
    }

    /**
     * The answer for a catalogue variant: the stock of the customers' SKUs drawn on it that inventory holds, by code;
     * empty when {@code asked} is no catalogue variant either, which stays NOT_FOUND.
     */
    private Optional<Answer> ofVariant(Sku asked) {
        if (customerSkus == null) {
            return Optional.empty();
        }
        Optional<CustomerSkus.DrawnSkus> drawn = customerSkus.drawnOn(asked.code(), DRAWN_READ);
        if (drawn.isEmpty()) {
            return Optional.empty();
        }
        List<CustomerSku> all = drawn.get().skus();
        Map<String, UUID> stocked = all.isEmpty() ? Map.of()
                : inventory.itemIdsOf(all.stream().map(CustomerSku::sku).toList());
        List<CustomerSku> held = all.stream().filter(customerSku -> stocked.containsKey(customerSku.sku())).toList();
        // The ones with a warehouse the user may see (levelsOf applies the warehouse limit), one more than is listed
        // so the answer knows some were left out, reading at most LEVELS_READ.
        List<Visible> visible = new ArrayList<>();
        int read = 0;
        for (CustomerSku customerSku : held) {
            if (visible.size() > MAX_CUSTOMER_SKUS || read >= LEVELS_READ) {
                break;
            }
            read++;
            Sku code = new Sku(customerSku.sku());
            List<StockLevel> levels = inventory.levelsOf(code);
            if (!levels.isEmpty()) {
                visible.add(new Visible(customerSku, code, levels));
            }
        }
        List<Visible> listed = visible.subList(0, Math.min(MAX_CUSTOMER_SKUS, visible.size()));
        Map<UUID, String> names = namesOf(listed.stream().map(Visible::customerSku).toList());
        Map<String, Packing> packings = Packing.of(units, listed.stream().map(v -> v.sku().code()).toList());
        Company company = companyOf(listed, packings);
        List<CustomerSkuStock> rows = listed.stream().map(v -> {
            Packing packing = packings.getOrDefault(v.sku().code(), Packing.NONE);
            UUID owner = v.customerSku().ownerCustomerId();
            CustomerName customer = names.containsKey(owner) ? new CustomerName(owner, names.get(owner)) : null;
            return new CustomerSkuStock(v.sku().code(), customer, packing.packingUnit(), packing.unitsPerPacking(),
                    company.units(v.sku()), company.cartons(v.sku()), rowsOf(v.sku(), v.levels()));
        }).toList();
        // Exact when every stocked SKU was read; past MAX_CUSTOMER_SKUS + 1 visible, LEVELS_READ or DRAWN_READ the
        // count is a floor, and the answer says some were left out.
        long total = visible.size();
        boolean truncated = visible.size() > rows.size() || read < held.size() || drawn.get().total() > all.size();
        return Optional.of(new Answer(asked.code(), null, null, null, null, List.of(), true, rows, total,
                truncated));
    }

    /** A stocked customer SKU with a warehouse the user may see, and those warehouses' levels. */
    private record Visible(CustomerSku customerSku, Sku sku, List<StockLevel> levels) {
    }

    /** The company figures of the listed SKUs; empty for a user limited to particular warehouses. */
    private record Company(Map<Sku, Integer> unitsToPromise, Map<Sku, Integer> cartonsToPromise) {

        static final Company NONE = new Company(Map.of(), Map.of());

        Integer units(Sku sku) {
            return unitsToPromise.get(sku);
        }

        Integer cartons(Sku sku) {
            return cartonsToPromise.get(sku);
        }
    }

    /** The listed SKUs' company figures: one query for the units, one for the cartons. */
    private Company companyOf(List<Visible> listed, Map<String, Packing> packings) {
        if (listed.isEmpty() || WarehouseScope.restriction().isPresent()) {
            return Company.NONE;
        }
        List<Sku> skus = listed.stream().map(Visible::sku).toList();
        Map<Sku, Integer> perCarton = new HashMap<>();
        skus.forEach(sku -> {
            Integer count = packings.getOrDefault(sku.code(), Packing.NONE).unitsPerPacking();
            if (count != null && count >= 1) {
                perCarton.put(sku, count);
            }
        });
        Map<Sku, Integer> units = inventory.availableToPromise(skus);
        return new Company(units == null ? Map.of() : units, companyCartons(perCarton));
    }

    /** The customers' names, for a caller who may read customers; none for anyone else. */
    private Map<UUID, String> namesOf(List<CustomerSku> listed) {
        boolean mayReadCustomers = currentUsers != null && currentUsers.current()
                .map(user -> user.can(ToolPermissions.CUSTOMERS, Action.READ)).orElse(false);
        if (!mayReadCustomers || customers == null || listed.isEmpty()) {
            return Map.of();
        }
        return customers.fullNames(listed.stream().map(CustomerSku::ownerCustomerId).filter(Objects::nonNull)
                .distinct().toList());
    }

    /**
     * The company's cartons of the standard count still to promise; null without a standard count, or when inventory
     * cannot say: the units still answer the question.
     */
    private Integer companyCartons(Sku sku, Packing packing) {
        if (packing.unitsPerPacking() == null || packing.unitsPerPacking() < 1) {
            return null;
        }
        return companyCartons(Map.of(sku, packing.unitsPerPacking())).get(sku);
    }

    /** The cartons still to promise of several SKUs in one query; a SKU is missing when they cannot be read. */
    private Map<Sku, Integer> companyCartons(Map<Sku, Integer> unitsPerCarton) {
        if (unitsPerCarton.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Sku, Integer> cartons = inventory.cartonsToPromise(unitsPerCarton);
            return cartons == null ? Map.of() : cartons;
        } catch (RuntimeException unreadable) {
            log.warn("Assistant stock levels of {} without the company cartons: they could not be read",
                    unitsPerCarton.keySet().stream().map(Sku::code).toList(), unreadable);
            return Map.of();
        }
    }

    /**
     * @param packingUnit               the variant's carton ({@code CARTON}); null when it has no carton specification
     * @param unitsPerPacking           the carton's standard count; null with no carton
     * @param companyAvailableToPromise what the company may still promise in units, every warehouse together and every
     *                                  order's hold subtracted; null for a user limited to particular warehouses
     * @param companyCartonsToPromise   cartons of {@code unitsPerPacking} still to promise company-wide; null with no
     *                                  carton, and for a user limited to particular warehouses
     * @param warehouses                empty when no warehouse the user may see holds or expects the SKU; never
     *                                  because the SKU is unknown, which is NOT_FOUND; empty for a catalogue variant
     * @param catalogueVariant          true when {@code sku} is a catalogue variant, which holds no stock: its goods are
     *                                  {@code customerSkus}; null for a SKU
     * @param customerSkus              for a catalogue variant, the customers' SKUs drawn on it that inventory holds,
     *                                  by code, at most {@value #MAX_CUSTOMER_SKUS}; null for a SKU
     * @param customerSkusTotal         every such SKU with a warehouse the user may see; a floor when
     *                                  {@code customerSkusTruncated}
     * @param customerSkusTruncated     some were left out
     */
    record Answer(String sku, String packingUnit, Integer unitsPerPacking, Integer companyAvailableToPromise,
                  Integer companyCartonsToPromise, List<Row> warehouses, Boolean catalogueVariant,
                  List<CustomerSkuStock> customerSkus, Long customerSkusTotal, Boolean customerSkusTruncated) {

        Answer(String sku, Integer companyAvailableToPromise, List<Row> warehouses) {
            this(sku, null, null, companyAvailableToPromise, null, warehouses, null, null, null, null);
        }
    }

    /**
     * One customer's SKU drawn on a catalogue variant, with its stock as a SKU's answer gives it.
     *
     * @param customer whose it is, written as the conversation's alias for the model; null for a caller who may not
     *                 read customers
     */
    record CustomerSkuStock(String sku, CustomerName customer, String packingUnit, Integer unitsPerPacking,
                            Integer companyAvailableToPromise, Integer companyCartonsToPromise, List<Row> warehouses) {
    }

    /**
     * Each figure in units and in cartons, as {@link StockLevel} counted them.
     *
     * @param inAvailableStatus  units in AVAILABLE status, before anything held on them is subtracted
     * @param heldOnStock        units held on this warehouse's stock: {@code StockLevel.reserved} and
     *                           {@code allocated} together, which the level keeps apart
     * @param allocatedToPicks   of {@code heldOnStock}, the units a release placed on cartons for picking
     * @param heldForOrders      units orders of signed contracts hold at this warehouse by SKU, on no carton
     * @param availableToPromise this warehouse's free whole cartons less {@code heldForOrders}, in units, capped at the
     *                           company figure; {@code availableToPromiseCartons} the same worked out count by count
     */
    record Row(String warehouseCode, int onHand, int onHandCartons, int inAvailableStatus, int availableCartons,
               int heldOnStock, int heldOnStockCartons, int allocatedToPicks, int allocatedCartons, int heldForOrders,
               int availableToPromise, int availableToPromiseCartons) {

        static Row of(StockLevel level) {
            return of(level, 0);
        }

        static Row of(StockLevel level, int heldForOrders) {
            return new Row(level.warehouseCode(), level.onHand(), level.onHandCartons(), level.available(),
                    level.availableCartons(), level.reserved() + level.allocated(),
                    level.reservedCartons() + level.allocatedCartons(), level.allocated(), level.allocatedCartons(),
                    heldForOrders, level.atp(), level.atpCartons());
        }
    }
}
