package com.stockflow.reporting.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.export.ExportResponses;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.reporting.internal.controller.dto.StockSummaryResponse;
import com.stockflow.reporting.internal.domain.StockSummaryLine;
import com.stockflow.reporting.internal.service.StockSummaryReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The stock summary, "xuất–nhập–tồn" (2026-11-14 plan, BE2-C; kltn-docs 20 §4.7): per SKU and warehouse over a
 * period, from the stock ledger. {@code reporting-stock-summary:READ} for the warehouse manager, the Sales Manager and
 * the accountant; the Excel file is {@code :EXPORT}. The warehouse manager is limited to their warehouses (scope
 * WAREHOUSE): naming another is a 403, and without a warehouse the rows are theirs only.
 *
 * <p>A row drills down to its ledger lines with {@code GET /api/v1/inventory/movements?sku=&warehouseId=&from=&to=}.</p>
 */
@RestController
@RequestMapping("/api/v1/reports/stock-summary")
@Tag(name = "Stock summary report", description = "Stock in, out and on hand per SKU and warehouse over a period, "
        + "in pieces and cartons, from the stock ledger (kltn-docs 20 §4.7)")
@PermissionResource(
        code = ReportingResources.STOCK_SUMMARY,
        group = "Reports",
        label = "Stock summary",
        route = "/reports/stock-summary",
        apiPath = "/api/v1/reports/stock-summary",
        actions = {Action.VIEW_PAGE, Action.READ, Action.EXPORT})
class StockSummaryReportController {

    private static final String FROM = "First day, inclusive, ISO date. Default: 29 days before 'to'";
    private static final String TO = "Last day, inclusive, ISO date. Default: today (Asia/Ho_Chi_Minh)";
    private static final String WAREHOUSE = "One warehouse; 404 WAREHOUSE_NOT_FOUND, 403 when not the caller's";
    private static final String CUSTOMER = "The SKUs of one customer; 404 CUSTOMER_NOT_FOUND for an unknown one";
    private static final String INCLUDE_ZERO = "Keep rows whose every column is zero (stock that came and went "
            + "before the period). Default false";
    private static final String SORT = "sku (default), warehouse, openingUnits, receivedUnits, pickedUnits or "
            + "closingUnits, with ,asc or ,desc";

    private final StockSummaryReportService reports;

    StockSummaryReportController(StockSummaryReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "Opening, received, returned, put back, picked, adjusted and closing stock per SKU and "
            + "warehouse, in pieces and cartons",
            description = "Needs reporting-stock-summary:READ. Business days in Asia/Ho_Chi_Minh, at most 366 (400 "
                    + "VALIDATION_FAILED otherwise). Moves inside a warehouse (putaway, carton moves, staging) change "
                    + "nothing. cartons is null, with cartonNote, when a column is not a whole number of cartons.")
    @RequiresPermission(resource = ReportingResources.STOCK_SUMMARY, action = Action.READ)
    public ApiResponse<StockSummaryResponse> summary(
            @Parameter(description = FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = WAREHOUSE) @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = CUSTOMER) @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String sku,
            @Parameter(description = INCLUDE_ZERO) @RequestParam(defaultValue = "false") boolean includeZero,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @Parameter(description = SORT) @RequestParam(required = false) String sort) {
        var summary = reports.summary(new StockSummaryReportService.Filter(from, to, warehouseId, customerId, sku,
                includeZero), page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort);
        return ApiResponse.ok(new StockSummaryResponse(summary.from(), summary.to(),
                summary.rows().map(StockSummaryReportController::toRow)));
    }

    @GetMapping("/export")
    @Operation(summary = "The stock summary as XLSX: every row of the filter, each column in cartons and pieces",
            description = "Needs reporting-stock-summary:EXPORT. Same filters and sort as the summary. 422 "
                    + "EXPORT_TOO_LARGE past 50,000 rows.")
    @RequiresPermission(resource = ReportingResources.STOCK_SUMMARY, action = Action.EXPORT)
    public ResponseEntity<byte[]> export(
            @Parameter(description = FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = WAREHOUSE) @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = CUSTOMER) @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String sku,
            @Parameter(description = INCLUDE_ZERO) @RequestParam(defaultValue = "false") boolean includeZero,
            @Parameter(description = SORT) @RequestParam(required = false) String sort) {
        return ExportResponses.xlsx(reports.export(new StockSummaryReportService.Filter(from, to, warehouseId,
                customerId, sku, includeZero), sort));
    }

    private static StockSummaryResponse.Row toRow(StockSummaryLine line) {
        return new StockSummaryResponse.Row(line.warehouseId(), line.warehousePrefix(), line.warehouseName(),
                line.sku(), line.ownerId(), line.ownerCode(), line.ownerName(), line.unitsPerCarton(),
                quantity(line, line.openingUnits()), quantity(line, line.receivedUnits()),
                quantity(line, line.returnedUnits()), quantity(line, line.putBackUnits()),
                quantity(line, line.pickedUnits()), quantity(line, line.adjustedInUnits()),
                quantity(line, line.adjustedOutUnits()), quantity(line, line.otherInUnits()),
                quantity(line, line.otherOutUnits()), quantity(line, line.closingUnits()),
                line.cartonNote().name());
    }

    private static StockSummaryResponse.Quantity quantity(StockSummaryLine line, long units) {
        return new StockSummaryResponse.Quantity(units, line.cartons(units));
    }
}
