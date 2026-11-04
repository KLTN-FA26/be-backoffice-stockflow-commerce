package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.internal.controller.dto.AcknowledgeAlertRequest;
import com.stockflow.inventory.internal.controller.dto.StockAlertResponse;
import com.stockflow.inventory.internal.controller.dto.StockAlertSummaryResponse;
import com.stockflow.inventory.internal.controller.dto.UnconfiguredSkuResponse;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.service.StockAlerts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Low-stock and expiry alerts (SCRUM-147, 165, 166): the list behind the bell's "see all", with acknowledge.
 *
 * <p><b>Scope.</b> {@code inventory-alerts} is held by the warehouse manager, the inventory planner, warehouse staff
 * (read only) and SYSTEM_ADMIN, never by CUSTOMER, so no customer reaches these endpoints (403 at the guard) and
 * {@code ScopeGuards.requireBackOffice} is not needed. The warehouse limit is applied in the service: a
 * warehouse-limited user sees the expiry alerts of the warehouses they are assigned to and none of the SKU-wide
 * REORDER/SAFETY ones; asking for another warehouse by id is {@code 403 OUT_OF_DATA_SCOPE}.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory/alerts")
@Tag(name = "Inventory alerts", description = "Low stock and expiry alerts")
@PermissionResource(
        code = InventoryResources.ALERTS,
        group = "Inventory",
        label = "Stock alerts",
        route = "/inventory/alerts",
        apiPath = "/api/v1/inventory/alerts",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
class StockAlertController {

    private final StockAlerts alerts;
    private final StockAlertWebMapper mapper;

    StockAlertController(StockAlerts alerts, StockAlertWebMapper mapper) {
        this.alerts = alerts;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Alerts, newest first; filter by kind, status, SKU and warehouse")
    @RequiresPermission(resource = InventoryResources.ALERTS, action = Action.READ)
    public ApiResponse<PageResponse<StockAlertResponse>> list(
            @RequestParam(required = false) Set<StockAlert.Kind> kind,
            @RequestParam(required = false) Set<StockAlert.Status> status,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(alerts.list(kind, status, sku, warehouseId, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(mapper::toResponse));
    }

    @GetMapping("/summary")
    @Operation(summary = "How many alerts are open per kind (for the cards above the list)")
    @RequiresPermission(resource = InventoryResources.ALERTS, action = Action.READ)
    public ApiResponse<StockAlertSummaryResponse> summary() {
        Map<String, Long> byKind = new LinkedHashMap<>();
        alerts.summary().forEach((kind, count) -> byKind.put(kind.name(), count));
        return ApiResponse.ok(new StockAlertSummaryResponse(byKind));
    }

    @GetMapping("/unconfigured")
    @Operation(summary = "SKUs with stock and no reorder point; 403 for a warehouse-limited caller")
    @RequiresPermission(resource = InventoryResources.ALERTS, action = Action.READ)
    public ApiResponse<PageResponse<UnconfiguredSkuResponse>> unconfigured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(alerts.unconfigured(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }

    @GetMapping("/{alertId}")
    @Operation(summary = "One alert")
    @RequiresPermission(resource = InventoryResources.ALERTS, action = Action.READ)
    public ApiResponse<StockAlertResponse> get(@PathVariable UUID alertId) {
        return ApiResponse.ok(mapper.toResponse(alerts.get(alertId)));
    }

    @PostMapping("/{alertId}/acknowledgement")
    @Operation(summary = "Say the alert has been seen; the condition stays open until it clears")
    @RequiresPermission(resource = InventoryResources.ALERTS, action = Action.UPDATE)
    public ApiResponse<StockAlertResponse> acknowledge(@PathVariable UUID alertId,
                                                       @Valid @RequestBody AcknowledgeAlertRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(alerts.acknowledge(alertId, request.version(), request.note(),
                user.userId())));
    }
}
