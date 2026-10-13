package com.stockflow.production.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.production.internal.controller.dto.ProductionOrderRowResponse;
import com.stockflow.production.internal.controller.dto.SampleRequestRowResponse;
import com.stockflow.production.internal.service.ProductionDashboard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The workshop dashboard (SCRUM-415). Read access to production orders is what it takes: the dashboard shows
 * nothing a production order list would not. The samples list needs no extra grant either — it shows rounds
 * the caller's warehouses make, as counts beside the orders.
 */
@RestController
@RequestMapping("/api/v1/production/dashboard")
@Tag(name = "Production dashboard", description = "Workshop overview: in progress, overdue, scrap rate, samples waiting")
class ProductionDashboardController {

    private final ProductionDashboard dashboard;
    private final ProductionWebMapper mapper;

    ProductionDashboardController(ProductionDashboard dashboard, ProductionWebMapper mapper) {
        this.dashboard = dashboard;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Open production orders by status, overdue count, samples awaiting the customer, and the scrap "
            + "rate of the production orders CREATED from 'from' to 'to' (periodBasis in the answer says so)")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.READ)
    public ApiResponse<ProductionDashboard.Summary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(dashboard.summary(user, from, to));
    }

    @GetMapping("/overdue")
    @Operation(summary = "Open production orders past their due date, most overdue first")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<ProductionOrderRowResponse>> overdue(
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(dashboard.overdue(user, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }

    @GetMapping("/samples-awaiting-approval")
    @Operation(summary = "Sample rounds sent to the customer and not answered yet, oldest first")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<SampleRequestRowResponse>> samplesAwaitingApproval(
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(dashboard.samplesAwaitingApproval(user, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }
}
