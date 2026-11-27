package com.stockflow.reporting.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.export.ExportFile;
import com.stockflow.common.export.ExportLabels;
import com.stockflow.common.export.XlsxTable;
import com.stockflow.common.export.XlsxTable.Column;
import com.stockflow.common.export.XlsxTable.Kind;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.inventory.api.StockLedgerTotals;
import com.stockflow.inventory.api.StockMovementTotals;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.reporting.internal.domain.ReportPeriod;
import com.stockflow.reporting.internal.domain.StockSummaryLine;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * The stock summary - stock in, out and on hand per SKU and warehouse over a period ("xuất–nhập–tồn"; 2026-11-14
 * plan, BE2-C; kltn-docs 20 §4.7) - in pieces and cartons, with the customer each SKU belongs to: a customer's own SKUs
 * are the only goods stocked (12 §4.5), so "the goods of customer A in our warehouses" is a question Sales and the
 * warehouse manager ask. Quantities only: the system values no stock (no cost, no valuation).
 *
 * <p>The figures are the inventory module's ({@link StockLedgerTotals}, one statement over the stock ledger); this
 * service names them - the owner ({@link CustomerSkus}, {@link CustomerSummaries}), the warehouse
 * ({@link WarehouseService}), the pieces per carton ({@link UomConversionService}) - and filters, sorts and pages in
 * memory: a row is a SKU in a warehouse, a few hundred at most.</p>
 *
 * <p>The period is business days (Asia/Ho_Chi_Minh), at most {@value ReportPeriod#MAX_DAYS} of them, the last 30 by
 * default ({@link ReportPeriod}). A caller limited to warehouses sees only theirs: a warehouse named outside them is a
 * 403, and without one the ledger read is cut down to theirs ({@code WarehouseScope}).</p>
 */
@Service
@Transactional(readOnly = true)
public class StockSummaryReportService {

    /** Sortable by SKU (the default), warehouse, or the opening, received, picked or closing pieces. */
    static final SortWhitelist SORT = SortWhitelist.of("sku", "warehouse", "openingUnits", "receivedUnits",
            "pickedUnits", "closingUnits").withDefault("sku", Sort.Direction.ASC);

    /**
     * What the summary is about. {@code from} / {@code to} null: the last 30 days up to today.
     *
     * @param includeZero keep the rows whose every column is zero (stock that came and went before the period)
     */
    public record Filter(LocalDate from, LocalDate to, UUID warehouseId, UUID customerId, String sku,
                         boolean includeZero) {
    }

    /** A page of rows of the period. */
    public record StockSummary(LocalDate from, LocalDate to, PageResponse<StockSummaryLine> rows) {
    }

    private final StockLedgerTotals ledger;
    private final UomConversionService units;
    private final CustomerSkus customerSkus;
    private final CustomerSummaries customers;
    private final WarehouseService warehouses;
    private final Messages messages;
    private final Clock clock;

    StockSummaryReportService(StockLedgerTotals ledger, UomConversionService units, CustomerSkus customerSkus,
                              CustomerSummaries customers, WarehouseService warehouses, Messages messages,
                              Clock clock) {
        this.ledger = ledger;
        this.units = units;
        this.customerSkus = customerSkus;
        this.customers = customers;
        this.warehouses = warehouses;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * @throws BusinessException {@code VALIDATION_FAILED} for a period over 366 days or inverted, or a sort outside
     *                           the list; {@code UNSUPPORTED_PARAMETER} for a bad page or size;
     *                           {@code WAREHOUSE_NOT_FOUND}; {@code CUSTOMER_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}
     *                           for a warehouse not the caller's
     */
    public StockSummary summary(Filter filter, int page, int size, String sort) {
        Pageable pageable = Pages.of(page, size, SORT.parse(sort));
        Report report = report(filter, pageable.getSort());
        List<StockSummaryLine> lines = report.lines();
        int from = (int) Math.min(pageable.getOffset(), lines.size());
        int to = Math.min(from + pageable.getPageSize(), lines.size());
        return new StockSummary(report.period().from(), report.period().to(),
                PageResponse.of(lines.subList(from, to), page, size, lines.size()));
    }

    /**
     * The summary as XLSX: every row of the filter, each column in cartons and in pieces. Audited as an EXPORT with
     * its filter.
     */
    @Auditable(action = AuditAction.EXPORT, resourceType = "stock-summary-report",
            details = "{from: #filter.from(), to: #filter.to(), warehouseId: #filter.warehouseId(), "
                    + "customerId: #filter.customerId(), sku: #filter.sku(), includeZero: #filter.includeZero()}")
    public ExportFile export(Filter filter, String sort) {
        Instant now = clock.instant();
        Report report = report(filter, SORT.parse(sort));
        List<String> parts = new ArrayList<>();
        if (report.warehouse() != null) {
            parts.add(label("filter.warehouse", "warehouse {0}", report.warehouse().prefix() + " "
                    + report.warehouse().name()));
        }
        if (report.customer() != null) {
            parts.add(label("filter.customer", "customer {0}", report.customer().code() + " "
                    + report.customer().fullName()));
        }
        if (report.sku() != null) {
            parts.add(label("filter.sku", "SKU {0}", report.sku()));
        }
        if (filter.includeZero()) {
            parts.add(label("filter.include-zero", "rows with nothing on hand and no movement included"));
        }
        List<String> titles = List.of(label("title", "Stock in, out and on hand by SKU and warehouse"),
                label("period", "Period {0} - {1}", ExportLabels.day(report.period().from()),
                        ExportLabels.day(report.period().to())),
                ExportLabels.filterLine(messages, parts), ExportLabels.exportedAt(messages, now));
        List<Column<StockSummaryLine>> columns = new ArrayList<>(List.of(
                new Column<>(label("column.warehouse", "Warehouse"), Kind.TEXT, StockSummaryLine::warehousePrefix),
                new Column<>(label("column.warehouse-name", "Warehouse name"), Kind.TEXT,
                        StockSummaryLine::warehouseName),
                new Column<>(label("column.sku", "SKU"), Kind.TEXT, StockSummaryLine::sku),
                new Column<>(label("column.owner-code", "Customer code"), Kind.TEXT, StockSummaryLine::ownerCode),
                new Column<>(label("column.owner-name", "Customer"), Kind.TEXT, StockSummaryLine::ownerName),
                new Column<>(label("column.units-per-carton", "Pieces per carton"), Kind.INTEGER,
                        StockSummaryLine::unitsPerCarton)));
        measure(columns, "opening", "Opening", StockSummaryLine::openingUnits);
        measure(columns, "received", "Received (production orders)", StockSummaryLine::receivedUnits);
        measure(columns, "returned", "Returned goods received", StockSummaryLine::returnedUnits);
        measure(columns, "put-back", "Put back after picking", StockSummaryLine::putBackUnits);
        measure(columns, "picked", "Picked out", StockSummaryLine::pickedUnits);
        measure(columns, "adjusted-in", "Adjusted up", StockSummaryLine::adjustedInUnits);
        measure(columns, "adjusted-out", "Adjusted down", StockSummaryLine::adjustedOutUnits);
        measure(columns, "other-in", "Other in", StockSummaryLine::otherInUnits);
        measure(columns, "other-out", "Other out", StockSummaryLine::otherOutUnits);
        measure(columns, "closing", "Closing", StockSummaryLine::closingUnits);
        columns.add(new Column<>(label("column.note", "Note"), Kind.TEXT, line -> switch (line.cartonNote()) {
            case NONE -> null;
            case NO_CARTON_UNIT -> label("note.no-carton-unit", "The SKU has no carton unit: cartons not counted");
            case NOT_WHOLE_CARTONS -> label("note.not-whole-cartons",
                    "Not a whole number of cartons: cartons not counted");
        }));
        byte[] file = XlsxTable.write(label("sheet", "Stock summary"), titles, columns, report.lines());
        return new ExportFile(ExportLabels.filename("stock-summary", now), file);
    }

    /** A measure as two columns, cartons then pieces, as the business reads stock (20 §1: by carton, with pieces). */
    private void measure(List<Column<StockSummaryLine>> columns, String key, String fallback,
                         ToLongFunction<StockSummaryLine> pieces) {
        String name = label("column." + key, fallback);
        columns.add(new Column<>(label("unit.cartons", "{0} (cartons)", name), Kind.INTEGER,
                line -> line.cartons(pieces.applyAsLong(line))));
        columns.add(new Column<>(label("unit.pieces", "{0} (pieces)", name), Kind.INTEGER, pieces::applyAsLong));
    }

    /** The rows of a filter, sorted, with what the filter named. */
    private record Report(ReportPeriod period, WarehouseView warehouse, CustomerSummary customer, String sku,
                          List<StockSummaryLine> lines) {
    }

    private Report report(Filter filter, Sort sort) {
        ReportPeriod period = ReportPeriod.of(filter.from(), filter.to(), BusinessCalendar.date(clock.instant()));
        WarehouseView warehouse = null;
        List<String> prefixes = null;
        if (filter.warehouseId() != null) {
            WarehouseScope.requireWarehouse(filter.warehouseId());
            warehouse = warehouses.findWarehouse(filter.warehouseId()).orElseThrow(() -> new BusinessException(
                    ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + filter.warehouseId()));
            prefixes = List.of(warehouse.prefix());
        }
        String sku = filter.sku() == null || filter.sku().isBlank() ? null
                : filter.sku().strip().toUpperCase(Locale.ROOT);
        CustomerSummary customer = null;
        Set<String> skus = sku == null ? null : new LinkedHashSet<>(List.of(sku));
        if (filter.customerId() != null) {
            customer = customers.summariesOf(List.of(filter.customerId())).get(filter.customerId());
            if (customer == null) {
                throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND, "No customer " + filter.customerId());
            }
            Set<String> owned = new LinkedHashSet<>();
            customerSkus.ownedBy(filter.customerId(), null).stream().map(CustomerSku::sku)
                    .forEach(code -> owned.add(code.toUpperCase(Locale.ROOT)));
            if (skus != null) {
                owned.retainAll(skus);
            }
            skus = owned;
        }
        List<StockMovementTotals> totals = ledger.movementTotals(period.from(), period.to(), prefixes, skus).stream()
                .filter(row -> filter.includeZero() || !row.isEmpty())
                .toList();
        List<StockSummaryLine> lines = lines(totals, warehouse);
        return new Report(period, warehouse, customer, sku, lines.stream().sorted(comparator(sort)).toList());
    }

    private List<StockSummaryLine> lines(List<StockMovementTotals> totals, WarehouseView asked) {
        if (totals.isEmpty()) {
            return List.of();
        }
        Set<String> skus = new LinkedHashSet<>();
        totals.forEach(row -> skus.add(row.sku()));
        Map<String, UUID> owners = customerSkus.ownersOf(skus);
        Map<UUID, CustomerSummary> owner = customers.summariesOf(new HashSet<>(owners.values()));
        Map<String, Integer> perCarton = new HashMap<>();
        units.conversionsOf(skus.stream().map(Sku::new).toList()).forEach((code, list) ->
                UomConversion.cartonOf(list).ifPresent(carton -> perCarton.put(code, carton.factor())));
        Map<String, WarehouseView> byPrefix = new HashMap<>();
        warehouses.activeWarehouses().forEach(view -> byPrefix.put(view.prefix().toUpperCase(Locale.ROOT), view));
        if (asked != null) {
            byPrefix.put(asked.prefix().toUpperCase(Locale.ROOT), asked);
        }
        List<StockSummaryLine> lines = new ArrayList<>(totals.size());
        for (StockMovementTotals row : totals) {
            WarehouseView warehouse = byPrefix.get(row.warehousePrefix());
            UUID ownerId = owners.get(row.sku());
            CustomerSummary customer = ownerId == null ? null : owner.get(ownerId);
            lines.add(new StockSummaryLine(warehouse == null ? null : warehouse.id(), row.warehousePrefix(),
                    warehouse == null ? null : warehouse.name(), row.sku(), ownerId,
                    customer == null ? null : customer.code(), customer == null ? null : customer.fullName(),
                    perCarton.get(row.sku()), row.openingUnits(), row.receivedUnits(), row.returnedUnits(),
                    row.putBackUnits(), row.pickedUnits(), row.adjustedInUnits(), row.adjustedOutUnits(),
                    row.otherInUnits(), row.otherOutUnits(), row.closingUnits()));
        }
        return lines;
    }

    /** The order asked, then SKU and warehouse, so that the rows keep one order from page to page. */
    static Comparator<StockSummaryLine> comparator(Sort sort) {
        Comparator<StockSummaryLine> order = (a, b) -> 0;
        for (Sort.Order by : sort) {
            Comparator<StockSummaryLine> one = switch (by.getProperty()) {
                case "sku" -> Comparator.comparing(StockSummaryLine::sku);
                case "warehouse" -> Comparator.comparing(StockSummaryLine::warehousePrefix);
                case "openingUnits" -> Comparator.comparingLong(StockSummaryLine::openingUnits);
                case "receivedUnits" -> Comparator.comparingLong(StockSummaryLine::receivedUnits);
                case "pickedUnits" -> Comparator.comparingLong(StockSummaryLine::pickedUnits);
                case "closingUnits" -> Comparator.comparingLong(StockSummaryLine::closingUnits);
                default -> throw new IllegalStateException("Not a sort of this report: " + by.getProperty());
            };
            order = order.thenComparing(by.isDescending() ? one.reversed() : one);
        }
        Function<StockSummaryLine, String> sku = StockSummaryLine::sku;
        return order.thenComparing(sku).thenComparing(StockSummaryLine::warehousePrefix);
    }

    private String label(String key, String fallback, Object... arguments) {
        return messages.get("export.stock-summary." + key, fallback, arguments);
    }
}
