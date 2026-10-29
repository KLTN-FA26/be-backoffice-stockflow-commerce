package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.ReplenishmentEligibility;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierItemSummary;
import com.stockflow.procurement.api.SupplierService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Who sells a SKU and on what terms, or what one supplier sells: the supplier catalog a buyer reads before writing a
 * purchase order — "who do we buy EVAL-LID-90 from, what is the MOQ, how long does it take".
 *
 * <p><b>One guard for both questions.</b> The tool mirrors the SKU's supplier list; a supplier's own catalog
 * ({@code GET /api/v1/suppliers/{supplierId}/items}) carries the same {@code procurement-supplier-items} READ, so
 * asking by supplier opens nothing the permission does not already show. The catalog is company-wide master data,
 * shown to every holder of the permission whatever their warehouses: there is no warehouse rule to repeat.</p>
 *
 * <p><b>Whether a row can be ordered.</b> The catalog keeps the offers of an inactive or blacklisted supplier, and of
 * a SKU that may not be bought any more (discontinued, suspended), while the purchase order form refuses both. Terms
 * read off such a row send a buyer to a form that turns them away, so every row says whether it can go on a new order,
 * through the same {@link ReplenishmentEligibility} check the form and the stock alerts make.</p>
 *
 * <p><b>Prices are before VAT.</b> The catalog price is what a purchase order line takes as its unit price; the
 * line's tax rate is added on the order, whose total findPurchaseOrders reports with VAT included.</p>
 */
@Component
class GetSupplierTermsTool implements AssistantTool {

    static final int MAX_ROWS = 50;

    private static final String ACTIVE = "ACTIVE";

    private static final ToolSpec SPEC = new ToolSpec("getSupplierTerms",
            "The supplier catalog: which suppliers sell a SKU and on what terms, or which SKUs one supplier sells. "
                    + "Each row: SKU, supplier code, name and status, whether it is the SKU's preferred supplier, the "
                    + "supplier's own code for the SKU, the current purchase price per unit before VAT and its "
                    + "currency, minimum order quantity (moq), pack size (orders are in whole packs) and lead time in "
                    + "days. A row can go on a new purchase order only when orderable is true: the supplier must be "
                    + "ACTIVE and the SKU still purchasable; when a row is not orderable, say why. Give a SKU, a "
                    + "supplier, or both for that supplier's terms on that SKU. A supplier's SKUs come newest listing "
                    + "first. When several suppliers match the supplier text, no terms are returned: the candidates "
                    + "are, so ask which one or call again with its code. When total is above the rows returned, say "
                    + "how many were left out.",
            ToolSchema.object()
                    .string("sku", "The SKU code exactly as the user gave it or a tool returned it, never guessed; an empty string to list "
                            + "everything the supplier sells")
                    .string("supplier", "The supplier's code or name, or part of it; an empty string for every "
                            + "supplier of the SKU")
                    .build());

    private final SupplierItemService catalog;
    private final SupplierService suppliers;
    private final ObjectProvider<ReplenishmentEligibility> eligibility;

    GetSupplierTermsTool(SupplierItemService catalog, SupplierService suppliers,
                         ObjectProvider<ReplenishmentEligibility> eligibility) {
        this.catalog = catalog;
        this.suppliers = suppliers;
        this.eligibility = eligibility;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/procurement/skus/{sku}/suppliers";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.SUPPLIER_ITEMS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String skuText = arguments.optionalText("sku", Sku.MAX_LENGTH);
        String supplierText = arguments.optionalText("supplier", 100);
        if (skuText == null && supplierText == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "give a SKU, a supplier, or both");
        }
        String sku = skuText == null ? null : new Sku(skuText).code();
        SupplierMatch supplier = null;
        if (supplierText != null) {
            supplier = SupplierMatch.find(suppliers, supplierText);
            if (!supplier.found()) {
                return new Answer(List.of(), 0, false, supplier.candidates(), supplier.note());
            }
        }
        return sku == null ? catalogOf(supplier) : offersFor(sku, supplier);
    }

    /** Every supplier of the SKU, preferred first, or only {@code supplier}'s offer when one was named. */
    private Answer offersFor(String sku, SupplierMatch supplier) {
        List<SkuSupplierSummary> offers = catalog.suppliersOf(sku).stream()
                .filter(offer -> supplier == null || offer.supplierId().equals(supplier.supplierId()))
                .toList();
        boolean purchasable = purchasable(List.of(sku)).contains(sku);
        List<Row> rows = offers.stream().limit(MAX_ROWS).map(offer -> Row.of(sku, offer, purchasable)).toList();
        String note = null;
        if (offers.isEmpty()) {
            // The purchase order form refuses a SKU its supplier does not list (SUPPLIER_ITEM_NOT_LISTED).
            note = supplier == null ? "No supplier lists " + sku + ", so no purchase order can take it until one does."
                    : supplier.code() + " does not list " + sku + ", so a purchase order to " + supplier.code()
                    + " cannot take it; ask without the supplier to see who sells it.";
        }
        return new Answer(rows, offers.size(), offers.size() > rows.size(), List.of(), note);
    }

    /** What one supplier lists, newest listing first: the catalog screen's own order. */
    private Answer catalogOf(SupplierMatch supplier) {
        PageResponse<SupplierItemSummary> page = catalog.list(supplier.supplierId(), 0, MAX_ROWS, null);
        Set<String> purchasable = purchasable(page.items().stream().map(SupplierItemSummary::sku).toList());
        List<Row> rows = page.items().stream()
                .map(item -> Row.of(supplier, item, purchasable.contains(item.sku()))).toList();
        return new Answer(rows, page.totalElements(), page.totalElements() > rows.size(), List.of(),
                rows.isEmpty() ? supplier.code() + " lists no SKU in its catalog." : null);
    }

    /** Without a product module (a module test) every SKU counts as purchasable, as StockAlertEvaluator does. */
    private Set<String> purchasable(List<String> skus) {
        ReplenishmentEligibility owner = eligibility.getIfAvailable();
        return owner == null ? Set.copyOf(skus) : owner.replenishable(skus);
    }

    /**
     * @param total              how many rows match; more than {@code terms} holds means some were left out
     * @param truncated          {@code total} is above the rows returned
     * @param supplierCandidates the suppliers the text matched when it did not name exactly one
     * @param note               why {@code terms} is empty; null otherwise
     */
    record Answer(List<Row> terms, long total, boolean truncated, List<SupplierMatch.Candidate> supplierCandidates,
                  String note) {
    }

    /**
     * @param supplierStatus     ACTIVE, INACTIVE or BLACKLISTED; only an ACTIVE supplier takes new orders
     * @param preferred          the SKU's default supplier, at most one per SKU, whom reorder proposals buy from while
     *                           it is ACTIVE
     * @param supplierSkuCode    the supplier's own code for the SKU; null when none was entered
     * @param unitPriceBeforeTax the current catalog price per unit, which a purchase order line takes before VAT
     * @param packSize           the order quantity must be a whole number of packs, and at least {@code moq}
     * @param leadTimeDays       what a purchase order plans with: the row's own lead time, else the supplier's
     * @param skuPurchasable     the SKU may still go on a new purchase order line (not discontinued or suspended)
     * @param orderable          both: a new purchase order to this supplier can take this SKU
     */
    record Row(String sku, String supplierCode, String supplierName, String supplierStatus, boolean preferred,
               String supplierSkuCode, BigDecimal unitPriceBeforeTax, String currency, int moq, int packSize,
               int leadTimeDays, boolean skuPurchasable, boolean orderable) {

        static Row of(String sku, SkuSupplierSummary offer, boolean skuPurchasable) {
            return new Row(sku, offer.supplierCode(), offer.supplierName(), offer.supplierStatus(), offer.preferred(),
                    offer.supplierSkuCode(), offer.price(), offer.currency(), offer.moq(), offer.packSize(),
                    offer.effectiveLeadTimeDays(), skuPurchasable,
                    skuPurchasable && ACTIVE.equals(offer.supplierStatus()));
        }

        static Row of(SupplierMatch supplier, SupplierItemSummary item, boolean skuPurchasable) {
            return new Row(item.sku(), supplier.code(), supplier.name(), supplier.status(), item.preferred(),
                    item.supplierSkuCode(), item.price(), item.currency(), item.moq(), item.packSize(),
                    item.effectiveLeadTimeDays(), skuPurchasable, skuPurchasable && ACTIVE.equals(supplier.status()));
        }
    }
}
