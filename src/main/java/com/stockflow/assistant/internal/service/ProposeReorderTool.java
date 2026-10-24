package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ReorderPolicy;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.IncomingStockQueries;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReplenishmentEligibility;
import com.stockflow.procurement.api.OnOrderQueries;
import com.stockflow.procurement.api.SkuOnOrder;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.reporting.api.ReportingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * U2: how much to reorder of some SKUs, as proposals a person turns into purchase orders (design §8).
 *
 * <p><b>Never a write.</b> The answer carries proposals the frontend pre-fills into the existing create-PO form; the
 * user edits one and submits it through that form's endpoint, under its own permission, audit and idempotency. The
 * quantity is {@link ReorderPolicy}'s arithmetic, not the model's.</p>
 *
 * <p><b>One proposal per supplier.</b> A purchase order has one supplier, and the form refuses a SKU the supplier
 * does not list and a quantity that is not a multiple of <i>its</i> pack; the rounding here was done for that
 * supplier. A SKU with no active supplier, or one that may not be bought any more (ReplenishmentEligibility, the
 * same check the alerts screen and the PO form make), gets a line with its reasons and no proposal.</p>
 *
 * <p><b>Whole days.</b> Sales are averaged over the days up to yesterday: today is still running, and counting it
 * as a full day understates the rate.</p>
 *
 * <p><b>Permissions.</b> The tool mirrors the action the proposal leads to, {@code POST /api/v1/purchase-orders}. A
 * proposal also shows sales figures, stock and supplier terms, so the asker must be allowed to read each of them on
 * its own screen: {@link CurrentUser#require} checks the three, "the checks a service makes beyond the endpoint's".
 * The figures are company-wide, so a user limited to particular warehouses is refused, as {@code findLowStock}
 * refuses them. Under the seeded grants (2026-10-25) only SYSTEM_ADMIN holds all four; which roles should is an open
 * question for the business (design §13 AQ-08).</p>
 */
@Component
class ProposeReorderTool implements AssistantTool {

    static final int MAX_SKUS = 10;

    private static final ToolSpec SPEC = new ToolSpec("proposeReorder",
            "Propose purchase quantities for up to 10 SKUs. The target is the average daily units ordered by "
                    + "customers over the last salesDays whole days (today excluded) times the supplier lead time "
                    + "plus coverDays, plus safety stock, capped at the SKU's maximum stock; from it is subtracted the "
                    + "company-wide position: available to promise, units on approved purchase orders not yet "
                    + "received, units received but not yet put away, and units on transfers between warehouses. The "
                    + "result is rounded up to the preferred supplier's minimum order and pack size. Returns every "
                    + "input per SKU, warnings, and one proposal per supplier the user can open as a purchase order "
                    + "form. It creates nothing. Explain the numbers and every warning; never change the numbers. "
                    + "Units on draft or pending-approval purchase orders are reported, not subtracted: mention them.",
            ToolSchema.object()
                    .strings("skus", "The SKU codes, 1 to " + MAX_SKUS)
                    .integer("salesDays", "Sales period to average over, in whole days ending yesterday, 7 to 90")
                    .integer("coverDays", "How many days the order should cover once it arrives, 7 to 120")
                    .build());

    private final ReportingService reports;
    private final InventoryService inventory;
    private final InventoryControlService control;
    private final IncomingStockQueries incoming;
    private final ObjectProvider<ReplenishmentEligibility> eligibility;
    private final SupplierItemService suppliers;
    private final OnOrderQueries purchasing;
    private final CurrentUserProvider currentUsers;
    private final Clock clock;

    ProposeReorderTool(ReportingService reports, InventoryService inventory, InventoryControlService control,
                       IncomingStockQueries incoming, ObjectProvider<ReplenishmentEligibility> eligibility,
                       SupplierItemService suppliers, OnOrderQueries purchasing, CurrentUserProvider currentUsers,
                       Clock clock) {
        this.reports = reports;
        this.inventory = inventory;
        this.control = control;
        this.incoming = incoming;
        this.eligibility = eligibility;
        this.suppliers = suppliers;
        this.purchasing = purchasing;
        this.currentUsers = currentUsers;
        this.clock = clock;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "POST /api/v1/purchase-orders";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.PURCHASE_ORDERS, action = Action.CREATE)
    public Answer run(ToolArguments arguments) {
        CurrentUser user = currentUsers.current()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        user.require(ToolPermissions.REPORTS, Action.READ);
        user.require(ToolPermissions.INVENTORY_STOCK_ITEMS, Action.READ);
        user.require(ToolPermissions.SUPPLIER_ITEMS, Action.READ);
        Set<String> skus = new LinkedHashSet<>();
        for (String code : arguments.texts("skus", MAX_SKUS, Sku.MAX_LENGTH)) {
            skus.add(new Sku(code).code());
        }
        int salesDays = arguments.integer("salesDays", 7, 90);
        int coverDays = arguments.integer("coverDays", 7, 120);
        Instant now = clock.instant();
        if (WarehouseScope.restriction().isPresent()) {
            return new Answer(now, null, null, List.of(), List.of(), "Reorder proposals use company-wide stock and "
                    + "sales; a user limited to particular warehouses does not see them. Ask for one SKU's stock per "
                    + "warehouse instead.");
        }
        LocalDate to = BusinessDate.of(now).minusDays(1);
        LocalDate from = to.minusDays(salesDays - 1L);
        Map<String, ReportingService.SkuSales> sold = byCode(reports.salesBySku(skus, from, to),
                ReportingService.SkuSales::sku);
        Map<String, SkuOnOrder> ordered = byCode(purchasing.onOrder(skus), SkuOnOrder::sku);
        Map<String, IncomingStockQueries.IncomingStock> arriving = byCode(incoming.incoming(skus),
                IncomingStockQueries.IncomingStock::sku);
        // Without a product module (a module test) every SKU counts as purchasable, as StockAlertEvaluator does.
        ReplenishmentEligibility owner = eligibility.getIfAvailable();
        Set<String> purchasable = owner == null ? skus : owner.replenishable(skus);

        List<Line> lines = skus.stream().map(code -> line(code, sold.get(code), ordered.get(code),
                arriving.get(code), purchasable.contains(code), from, salesDays, coverDays)).toList();
        return new Answer(now, from, to, lines, proposals(lines, now), null);
    }

    private Line line(String code, ReportingService.SkuSales sales, SkuOnOrder onOrder,
                      IncomingStockQueries.IncomingStock arriving, boolean purchasable, LocalDate from, int salesDays,
                      int coverDays) {
        Sku sku = new Sku(code);
        long units = sales == null ? 0 : sales.quantitySold();
        LocalDate firstSoldOn = sales == null ? null : sales.firstSoldOn();
        InventoryPolicy policy = control.policy(sku);
        int available = inventory.availableToPromise(sku);
        Optional<SkuSupplierSummary> supplier = preferred(suppliers.suppliersOf(code));
        int leadTime = supplier.map(SkuSupplierSummary::effectiveLeadTimeDays).orElse(0);
        int incomingUnits = arriving == null ? 0 : arriving.inbound() + arriving.inTransit();
        ReorderPolicy.Suggestion suggestion = ReorderPolicy.suggest(new ReorderPolicy.Inputs(units, salesDays,
                coverDays, leadTime, policy.safetyStock(), policy.maxQty(), available,
                onOrder == null ? 0 : onOrder.onOrder(), incomingUnits,
                supplier.map(SkuSupplierSummary::moq).orElse(null),
                supplier.map(SkuSupplierSummary::packSize).orElse(null)));

        List<String> warnings = new ArrayList<>();
        if (!purchasable) {
            warnings.add("NOT_PURCHASABLE");
        }
        if (supplier.isEmpty()) {
            warnings.add("NO_ACTIVE_SUPPLIER");
        }
        BigDecimal sinceFirstSale = null;
        if (firstSoldOn != null && firstSoldOn.isAfter(from.plusDays(salesDays / 2))) {
            warnings.add("SHORT_HISTORY");
            long days = ChronoUnit.DAYS.between(firstSoldOn, from.plusDays(salesDays - 1L)) + 1;
            sinceFirstSale = BigDecimal.valueOf(units).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        }
        if (onOrder != null && onOrder.awaitingApproval() > 0) {
            warnings.add("DRAFT_PURCHASE_ORDERS");
        }
        if (arriving != null && arriving.quarantine() > 0) {
            warnings.add("STOCK_ON_QC_HOLD");
        }
        boolean orderable = purchasable && supplier.isPresent();
        return new Line(code, orderable ? suggestion.quantity() : 0, List.copyOf(warnings),
                new Basis(units, salesDays, suggestion.averageDailySales(), firstSoldOn, sinceFirstSale,
                        supplier.isPresent() ? leadTime : null, coverDays, policy.safetyStock(), policy.maxQty(),
                        suggestion.target(), suggestion.cappedAtMax(), available,
                        onOrder == null ? 0 : onOrder.onOrder(), onOrder == null ? null : onOrder.nextExpectedDate(),
                        arriving == null ? 0 : arriving.inbound(), arriving == null ? 0 : arriving.inTransit(),
                        suggestion.position(), suggestion.need(), onOrder == null ? 0 : onOrder.awaitingApproval(),
                        arriving == null ? 0 : arriving.quarantine(),
                        supplier.map(SkuSupplierSummary::moq).orElse(null),
                        supplier.map(SkuSupplierSummary::packSize).orElse(null), suggestion.roundedFor()),
                supplier.map(SkuSupplierSummary::supplierCode).orElse(null),
                supplier.map(SkuSupplierSummary::supplierName).orElse(null));
    }

    /** The orderable lines grouped by supplier, in the order the SKUs were asked. */
    private static List<Proposal> proposals(List<Line> lines, Instant generatedAt) {
        Map<String, List<Line>> bySupplier = new LinkedHashMap<>();
        lines.stream().filter(line -> line.suggestedQuantity() > 0)
                .forEach(line -> bySupplier.computeIfAbsent(line.supplierCode(), code -> new ArrayList<>()).add(line));
        return bySupplier.values().stream()
                .map(group -> new Proposal("PURCHASE_ORDER_DRAFT", group.get(0).supplierCode(),
                        group.get(0).supplierName(), generatedAt,
                        group.stream().map(line -> new ProposalLine(line.sku(), line.suggestedQuantity())).toList()))
                .toList();
    }

    private static <T> Map<String, T> byCode(List<T> rows, Function<T, String> sku) {
        return rows.stream().collect(Collectors.toMap(sku, Function.identity(), (first, second) -> first));
    }

    /** The preferred supplier of the SKU, else the first active one. */
    private static Optional<SkuSupplierSummary> preferred(List<SkuSupplierSummary> candidates) {
        return candidates.stream()
                .filter(candidate -> "ACTIVE".equals(candidate.supplierStatus()))
                .min(Comparator.comparing((SkuSupplierSummary candidate) -> !candidate.preferred())
                        .thenComparing(SkuSupplierSummary::supplierCode));
    }

    /**
     * @param salesFrom the first day averaged; null when the user was refused
     * @param proposals one per supplier, empty when nothing needs ordering
     * @param note      why nothing was computed; null otherwise
     */
    record Answer(Instant generatedAt, LocalDate salesFrom, LocalDate salesTo, List<Line> lines,
                  List<Proposal> proposals, String note) {
    }

    /**
     * @param warnings NOT_PURCHASABLE, NO_ACTIVE_SUPPLIER (no proposal for either), SHORT_HISTORY (the SKU first sold
     *                 late in the period, so the average understates it), DRAFT_PURCHASE_ORDERS, STOCK_ON_QC_HOLD
     */
    record Line(String sku, long suggestedQuantity, List<String> warnings, Basis basis, String supplierCode,
                String supplierName) {
    }

    /** Every input of the suggestion, so a person can check it. */
    record Basis(long unitsSold, int salesDays, BigDecimal averageDailySales, LocalDate firstSoldOn,
                 BigDecimal averageSinceFirstSale, Integer leadTimeDays, int coverDays, Integer safetyStock,
                 Integer maxQty, long target, boolean cappedAtMax, int availableToPromise, int onOrder,
                 LocalDate nextExpectedDate, int inbound, int inTransit, long position, long need,
                 int onDraftPurchaseOrders, int onQcHold, Integer moq, Integer packSize, String roundedFor) {
    }

    /** What the frontend pre-fills into the create-PO form; the user decides and submits. */
    record Proposal(String type, String supplierCode, String supplierName, Instant generatedAt,
                    List<ProposalLine> lines) {
    }

    record ProposalLine(String sku, long quantity) {
    }
}
