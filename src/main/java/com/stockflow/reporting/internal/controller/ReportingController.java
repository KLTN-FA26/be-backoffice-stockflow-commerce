package com.stockflow.reporting.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.reporting.api.ReportingService;
import com.stockflow.reporting.internal.controller.dto.OrderStatusCountResponse;
import com.stockflow.reporting.internal.controller.dto.RebuildResponse;
import com.stockflow.reporting.internal.controller.dto.SalesDayResponse;
import com.stockflow.reporting.internal.controller.dto.SalesSummaryResponse;
import com.stockflow.reporting.internal.controller.dto.TopProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The sales figures of the back-office dashboard (SCRUM-344). Every read is {@code reporting-reports:READ}, scope ALL:
 * the figures are the business's, not a warehouse's or a customer's. The rebuild is {@code reporting-reports:EXPORT},
 * the one action of this resource that is not a read, held by the roles that administer reports.
 *
 * <p>Dates are ISO ({@code 2026-10-24}) and inclusive; both are optional and default to the last 30 days ending today
 * (Asia/Ho_Chi_Minh). A range over 366 days, or one that ends before it starts, is a 400.</p>
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Sales figures for the dashboard: revenue per day, top products, order status "
        + "counts, period summary. Revenue = orders placed less cancelled; ON_HOLD counts, DRAFT does not; VND.")
@PermissionResource(
        code = ReportingResources.REPORTS,
        group = "Reports",
        label = "Sales reports",
        route = "/reports",
        apiPath = "/api/v1/reports",
        actions = {Action.VIEW_PAGE, Action.READ, Action.EXPORT})
class ReportingController {

    private static final String FROM = "First day, inclusive, ISO date. Default: 29 days before 'to'";
    private static final String TO = "Last day, inclusive, ISO date. Default: today (Asia/Ho_Chi_Minh)";

    private final ReportingService reports;
    private final ReportingWebMapper mapper;

    ReportingController(ReportingService reports, ReportingWebMapper mapper) {
        this.reports = reports;
        this.mapper = mapper;
    }

    @GetMapping("/sales-daily")
    @Operation(summary = "Orders and revenue per day, one entry for every day of the range (zeros when no orders)",
            description = "Needs reporting-reports:READ. 400 VALIDATION_FAILED when the range is over 366 days or inverted.")
    @RequiresPermission(resource = ReportingResources.REPORTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<SalesDayResponse>> salesDaily(
            @Parameter(description = FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.salesDaily(from, to).stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/top-products")
    @Operation(summary = "The best-selling SKUs of the range, by revenue then units",
            description = "Needs reporting-reports:READ. productName is null if the product module does not know the SKU. "
                    + "limit 1..100, default 10 (400 otherwise).")
    @RequiresPermission(resource = ReportingResources.REPORTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<TopProductResponse>> topProducts(
            @Parameter(description = FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "How many products, 1..100") @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(reports.topProducts(from, to, limit).stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/order-status-counts")
    @Operation(summary = "How many orders are in each status right now (all statuses, ON_HOLD included; not date-bound)",
            description = "Needs reporting-reports:READ. Counted live from the order module.")
    @RequiresPermission(resource = ReportingResources.REPORTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<OrderStatusCountResponse>> orderStatusCounts() {
        return ApiResponse.ok(reports.orderStatusCounts().stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/summary")
    @Operation(summary = "Orders, revenue and average order value of the range, with the previous equally long period "
            + "and the percent change",
            description = "Needs reporting-reports:READ. The change is null when the previous period had no orders.")
    @RequiresPermission(resource = ReportingResources.REPORTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<SalesSummaryResponse> summary(
            @Parameter(description = FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(mapper.toResponse(reports.summary(from, to)));
    }

    @PostMapping("/rebuild")
    @Operation(summary = "Recompute the rollups of a range from the orders themselves (backfill / repair)",
            description = "Needs reporting-reports:EXPORT. Idempotent; the figures are the ones the event listeners "
                    + "produce. Runs synchronously, one transaction per day; range limits as for the reads.")
    @RequiresPermission(resource = ReportingResources.REPORTS, action = Action.EXPORT, scope = DataScope.ALL)
    public ApiResponse<RebuildResponse> rebuild(
            @Parameter(description = FROM) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = TO) @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(mapper.toResponse(reports.rebuild(from, to)));
    }
}
